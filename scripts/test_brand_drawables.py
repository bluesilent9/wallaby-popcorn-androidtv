"""Check branded Compose resources without starting an Android/Gradle build.

Android resolves value aliases before painterResource reads the asset. XML bitmap,
layer-list and adaptive-icon files cannot be passed to that Compose loader, even
when Android ImageView can display them. Check every qualified resource variant.
"""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"


def check_painter_resource(name, seen=()):
    if name in seen:
        raise AssertionError(f"Circular drawable alias: {seen} -> {name}")
    found = False
    for directory in RES.glob("drawable*"):
        for asset in directory.glob(f"{name}.*"):
            found = True
            if asset.suffix == ".xml":
                if ET.parse(asset).getroot().tag != "vector":
                    raise AssertionError(f"Compose cannot load {asset.relative_to(ROOT)}")
            elif asset.suffix == ".png":
                if not asset.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                    raise AssertionError(f"Invalid PNG: {asset}")
            else:
                raise AssertionError(f"Unexpected branded image format: {asset}")
    for values in RES.glob("values*/*.xml"):
        for item in ET.parse(values).getroot():
            if item.tag == "item" and item.get("type") == "drawable" and item.get("name") == name:
                found = True
                target = (item.text or "").strip()
                if not target.startswith("@drawable/"):
                    raise AssertionError(f"Unsupported drawable alias: {target}")
                check_painter_resource(target.removeprefix("@drawable/"), (*seen, name))
    if not found:
        raise AssertionError(f"Missing drawable: {name}")


class BrandDrawablesTest(unittest.TestCase):
    def test_logo_is_compose_compatible_in_every_android_configuration(self):
        check_painter_resource("app_logo")

    def test_cinema_background_is_compose_compatible(self):
        check_painter_resource("wallaby_cinema")


if __name__ == "__main__":
    unittest.main()
