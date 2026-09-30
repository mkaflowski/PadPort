"""Keep native EN/PL UI catalogs complete and format-compatible."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]/"app/src/main/res"

class LocalizationTest(unittest.TestCase):
    def setUp(self):
        self.en=ET.parse(ROOT/"values/strings.xml").getroot()
        self.pl=ET.parse(ROOT/"values-pl/strings.xml").getroot()

    def test_both_languages_have_all_translatable_strings(self):
        expected={n.attrib["name"] for n in self.en if n.get("translatable")!="false"}
        actual={n.attrib["name"] for n in self.pl}
        self.assertEqual(expected,actual)

    def test_format_arguments_are_preserved(self):
        translated={n.attrib["name"]:n for n in self.pl}
        pattern=r"%(?:\d+\$)?[.\d]*[sdf]"
        for original in self.en.findall("string"):
            if original.get("translatable")=="false":continue
            localized=translated[original.attrib["name"]]
            self.assertTrue(localized.text,original.attrib["name"])
            self.assertEqual(sorted(re.findall(pattern,original.text or "")),sorted(re.findall(pattern,localized.text or "")),original.attrib["name"])

    def test_gamepad_labels_keep_standard_button_indices(self):
        for root in (self.en,self.pl):
            labels=root.find("string-array[@name='gamepad_buttons']").findall("item")
            self.assertEqual(17,len(labels))
            self.assertTrue(labels[0].text.startswith("A /"))
            self.assertTrue(labels[6].text.startswith("L2"))
            self.assertIn("↑",labels[12].text)
            self.assertEqual("Guide",labels[16].text)

    def test_default_catalog_and_brand_are_english(self):
        strings={n.attrib["name"]:n.text for n in self.en.findall("string")}
        self.assertEqual("PadPort",strings["app_name"])
        self.assertEqual("Play",strings["play"])
        self.assertEqual("Play RPG Maker games on Android!",strings["tagline"])

    def test_webview_language_marker_tracks_native_resource_catalog(self):
        for root,language in ((self.en,"en"),(self.pl,"pl")):
            strings={n.attrib["name"]:n.text for n in root.findall("string")}
            self.assertEqual(language,strings["ui_language"])
            self.assertNotIn("language_button",strings)
            self.assertNotIn("language_title",strings)

if __name__=="__main__":unittest.main()
