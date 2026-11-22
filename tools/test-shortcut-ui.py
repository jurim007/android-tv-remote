"""Run against a disposable TVControl install with the default shortcuts.

Build: ./gradlew -I tools/shortcut-test.init.gradle :app:assembleDebug
Install: adb install -r app/build/outputs/apk/debug/app-debug.apk
Run: python3 tools/test-shortcut-ui.py com.jurim007.androidtvremote.shortcuttest
The test edits the disposable install's shortcut order and removes a shortcut.
"""

import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True)


def find(label):
    path = "/data/local/tmp/tvcontrol-shortcut-ui.xml"
    for _ in range(3):
        adb("shell", "rm", "-f", path)
        if "dumped" in adb("shell", "uiautomator", "dump", path):
            tree = ET.fromstring(adb("exec-out", "cat", path))
            break
        time.sleep(1)
    else:
        raise RuntimeError("Android did not return an accessibility hierarchy.")
    return next(
        (
            element for element in tree.iter("node")
            if element.get("package") == package
            and label in (element.get("text"), element.get("content-desc"))
        ),
        None,
    )


def center(element):
    left, top, right, bottom = map(int, re.findall(r"\d+", element.attrib["bounds"]))
    return (left + right) // 2, (top + bottom) // 2


def input_event(*arguments):
    adb("shell", "input", *map(str, arguments))
    time.sleep(0.35)


def tap(label, hold=False):
    node = find(label)
    assert node is not None, f"Missing {label}"
    x, y = center(node)
    if hold:
        input_event("swipe", x, y, x, y, 750)
    else:
        input_event("tap", x, y)


if len(sys.argv) != 2 or not sys.argv[1].endswith(".shortcuttest"):
    sys.exit("Use a disposable TVControl variant ending in .shortcuttest.")
package = sys.argv[1]
width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])

adb("shell", "am", "start", "-n", f"{package}/com.jurim007.androidtvremote.MainActivity")
time.sleep(1)
tap("YouTube", hold=True)
assert find("Edit") is None
assert find("Remove") is None

tap("Choose TV")
tap("Remote settings")
for _ in range(6):
    if find("TV app shortcuts") is not None:
        break
    input_event("swipe", width // 2, height * 3 // 5, width // 2, height // 3, 350)
tap("TV app shortcuts")

tap("YouTube")
tap("Edit")
assert find("Edit TV app") is not None
assert find("HTTPS SVG / image link (optional)") is not None
tap("Cancel")

tap("Disney+")
tap("Remove")
assert find("Remove Disney+?") is not None
tap("Remove")
assert find("Disney+") is None

_, row_y = center(find("YouTube"))
input_event("tap", width // 4, row_y)
left_menu_x, _ = center(find("Edit"))
input_event("keyevent", 4)
input_event("tap", width // 2, row_y)
right_menu_x, _ = center(find("Edit"))
assert right_menu_x - left_menu_x > width // 8
input_event("keyevent", 4)

first = find("YouTube")
second = find("Netflix")
assert first is not None and second is not None
x, first_y = center(first)
_, second_y = center(second)
assert first_y < second_y
adb("shell", f"input motionevent DOWN {x} {first_y}; sleep 0.8; "
    f"input motionevent MOVE {x} {second_y}; input motionevent UP {x} {second_y}")
assert center(find("YouTube"))[1] > center(find("Netflix"))[1]
assert find("Edit") is None
print("PASS settings-only management, tap anchoring, edit, removal, and hold-to-reorder")
