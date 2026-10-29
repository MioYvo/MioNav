import pathlib
p = pathlib.Path("android/app/src/main/kotlin/io/mionav/app/ConfirmActivity.kt")
s = p.read_text()
start = s.index("/**\n * Fixed order, so \"the second one\" always means the same line.")
end = s.index(")\n", s.index("private val ROUTE_COLORS")) + 2
new = '''/**
 * Fixed order, so "the second one" always means the same line.
 *
 * Morandi tones: muted enough to sit on a busy basemap, dark enough to stay readable on its white,
 * green and blue areas. The chosen route keeps full opacity and double width; the rest are dashed
 * and faded, so the eye goes to one line.
 */
private val ROUTE_COLORS = intArrayOf(
    0xFFB4544B.toInt(), // 陶土红，主选
    0xFF6E7B8B.toInt(), // 青灰
    0xFF9A8C7A.toInt(), // 灰驼
    0xFF7A6E86.toInt(), // 灰紫
    0xFF5F7A6B.toInt(), // 灰松绿
)
'''
s = s[:start] + new + s[end:]
p.write_text(s)
print("palette replaced cleanly")
