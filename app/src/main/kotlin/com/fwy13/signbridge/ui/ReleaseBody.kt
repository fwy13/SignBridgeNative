package com.fwy13.signbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.Normalizer
import java.util.Locale

data class ParsedReleaseBody(
    val title: String,
    val description: String,
    val contents: List<String>,
    val userInformation: List<Pair<String, String>>
)

private enum class ReleaseSection {
    DESCRIPTION,
    CONTENTS,
    USER
}

private fun normalizedHeading(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('đ', 'd')
        .replace('Đ', 'D')
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun releasePlainText(value: String): String =
    value
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()

fun parseReleaseBody(
    body: String,
    fallbackTitle: String
): ParsedReleaseBody {
    var title: String? = null
    var section = ReleaseSection.DESCRIPTION
    var insideFence = false

    val description = mutableListOf<String>()
    val contents = mutableListOf<String>()
    val users = mutableListOf<Pair<String, String>>()

    val headingRegex = Regex("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    val bulletRegex = Regex(
        "^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)(?:\\[[ xX]]\\s+)?(.*)$"
    )

    fun sectionFor(heading: String): ReleaseSection? {
        val normalized = normalizedHeading(heading)

        return when (normalized) {
            "contents",
            "content",
            "included files",
            "files",
            "assets",
            "noi dung",
            "danh sach noi dung",
            "tep dinh kem" -> ReleaseSection.CONTENTS

            "user",
            "users",
            "user information",
            "user info",
            "author",
            "authors",
            "author information",
            "nguoi dung",
            "thong tin nguoi dung",
            "tac gia",
            "thong tin tac gia" -> ReleaseSection.USER

            "description",
            "summary",
            "overview",
            "mo ta",
            "tong quan" -> ReleaseSection.DESCRIPTION

            else -> null
        }
    }

    fun isUserField(key: String): Boolean =
        normalizedHeading(key) in setOf(
            "author",
            "user",
            "username",
            "name",
            "github",
            "email",
            "created by",
            "uploaded by",
            "tac gia",
            "nguoi dung",
            "ten",
            "ten dang nhap",
            "nguoi tai"
        )

    fun addUser(line: String) {
        val separator = line.indexOf(':')

        if (separator > 0) {
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()

            if (value.isNotBlank()) {
                users += key to value
            }
        } else if (line.isNotBlank()) {
            users += "" to line
        }
    }

    body.replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .forEach { raw ->
            val trimmed = raw.trim()

            if (
                trimmed.startsWith("```") ||
                trimmed.startsWith("~~~")
            ) {
                insideFence = !insideFence
                return@forEach
            }

            if (insideFence || trimmed.isBlank()) {
                return@forEach
            }

            if (
                trimmed == "---" ||
                trimmed == "***" ||
                trimmed == "___"
            ) {
                return@forEach
            }

            val heading = headingRegex.matchEntire(raw)

            if (heading != null) {
                val level = heading.groupValues[1].length
                val headingText = releasePlainText(
                    heading.groupValues[2]
                )
                val recognizedSection = sectionFor(headingText)

                when {
                    recognizedSection != null -> {
                        section = recognizedSection
                    }

                    title == null && level == 1 -> {
                        title = headingText
                        section = ReleaseSection.DESCRIPTION
                    }

                    else -> {
                        // Preserve unknown headings instead of losing them.
                        section = ReleaseSection.DESCRIPTION
                        description += headingText
                    }
                }

                return@forEach
            }

            val bullet = bulletRegex.matchEntire(raw)
            val line = releasePlainText(
                bullet?.groupValues?.get(1) ?: trimmed
            )

            if (line.isBlank()) return@forEach

            // Also accept standalone bold section labels, e.g. **Contents:**
            val sectionLabel = sectionFor(line.removeSuffix(":"))

            if (sectionLabel != null) {
                section = sectionLabel
                return@forEach
            }

            val separator = line.indexOf(':')
            val fieldName = if (separator > 0) {
                line.substring(0, separator)
            } else {
                ""
            }

            when {
                section == ReleaseSection.USER -> addUser(line)

                separator > 0 && isUserField(fieldName) -> addUser(line)

                section == ReleaseSection.CONTENTS -> contents += line

                bullet != null -> contents += line

                else -> description += line
            }
        }

    return ParsedReleaseBody(
        title = title?.takeIf { it.isNotBlank() }
            ?: fallbackTitle.ifBlank { "Release" },
        description = description.joinToString("\n"),
        contents = contents.distinct(),
        userInformation = users.distinct()
    )
}

@Composable
fun ReleaseBodyDetails(
    body: String,
    fallbackTitle: String,
    modifier: Modifier = Modifier
) {
    val parsed = remember(body, fallbackTitle) {
        parseReleaseBody(body, fallbackTitle)
    }

    var expanded by remember(body, fallbackTitle) {
        mutableStateOf(false)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            parsed.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        if (parsed.description.isNotBlank()) {
            Text(
                parsed.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3
            )
        }

        if (parsed.contents.isNotEmpty()) {
            Text(
                bilingual("Nội dung", "Contents"),
                style = MaterialTheme.typography.labelLarge
            )

            val visibleContents = if (expanded) {
                parsed.contents
            } else {
                parsed.contents.take(4)
            }

            visibleContents.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "•",
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        item,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (parsed.userInformation.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        bilingual("Thông tin người dùng", "User information"),
                        style = MaterialTheme.typography.labelLarge
                    )

                    parsed.userInformation.forEach { (key, value) ->
                        Text(
                            if (key.isBlank()) value else "$key: $value",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (
            parsed.description.isNotBlank() ||
            parsed.contents.size > 4
        ) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    if (expanded) {
                        bilingual("Thu gọn", "Show less")
                    } else {
                        bilingual("Xem đầy đủ", "Show all")
                    }
                )
            }
        }
    }
}