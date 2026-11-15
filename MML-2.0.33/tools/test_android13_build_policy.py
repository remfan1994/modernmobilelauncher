import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD = (ROOT / 'app' / 'build.gradle').read_text()
MAIN_MANIFEST = (ROOT / 'app' / 'src' / 'main' / 'AndroidManifest.xml').read_text()
TEST_MANIFEST = (ROOT / 'app' / 'src' / 'androidTest' / 'AndroidManifest.xml').read_text()
ACTIONS = (ROOT / 'app' / 'src' / 'main' / 'java' / 'vip' / 'thatiam' / 'mml' / 'NotificationActions.java').read_text()
BUILDER = (ROOT / 'tools' / 'build_apk.py').read_text()
STYLES = (ROOT / 'app' / 'src' / 'main' / 'res' / 'values' / 'styles.xml').read_text()

class Android13BuildPolicyTest(unittest.TestCase):
    def test_gradle_and_manifests_target_api_33(self):
        self.assertIn('compileSdk 33', BUILD)
        self.assertIn('minSdk 33', BUILD)
        self.assertIn('targetSdk 33', BUILD)
        self.assertIn('android:targetSdkVersion="33"', MAIN_MANIFEST)
        self.assertIn('android:targetSdkVersion="33"', TEST_MANIFEST)

    def test_api34_notification_option_is_runtime_reflection_only(self):
        self.assertIn('getField("MODE_BACKGROUND_ACTIVITY_START_ALLOWED")', ACTIONS)
        self.assertIn('getMethod("setPendingIntentBackgroundActivityStartMode", int.class)', ACTIONS)
        self.assertNotIn('ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED', ACTIONS)
        self.assertNotIn('options.setPendingIntentBackgroundActivityStartMode(', ACTIONS)

    def test_api35_resource_names_are_not_used_with_compile_sdk_33(self):
        self.assertNotIn('windowOptOutEdgeToEdgeEnforcement', STYLES)

    def test_manual_builder_uses_platform_33_and_no_ndk(self):
        self.assertIn('platforms" / "android-33"', BUILDER)
        self.assertIn('"--target-sdk-version", "33"', BUILDER)
        self.assertIn('The Android NDK is not used.', BUILDER)
        self.assertNotIn('android-35', BUILDER)

if __name__ == '__main__':
    unittest.main()
