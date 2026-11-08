package com.notcode.mobile.ui

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import com.notcode.mobile.filesystem.ProjectPath

/** Only the bounded project index is authoritative; ambiguous basenames are not links. */
internal fun messageFileLinks(source: AnnotatedString, files: List<ProjectPath>): AnnotatedString {
    val exact = files.associateBy { it.value }
    val names = files.groupBy { it.name }.filterValues { it.size == 1 }.mapValues { it.value.single() }
    val candidates = names + exact
    if (candidates.isEmpty()) return source
    val alternatives = candidates.keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    if (alternatives.isEmpty()) return source
    val pattern = Regex("(?<![\\p{L}\\p{N}_./\\\\:@-])(?:$alternatives)(?![\\p{L}\\p{N}_/\\\\-]|\\.[\\p{L}\\p{N}_])")
    val urls = Regex("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+").findAll(source.text).map { it.range }.toList()
    return AnnotatedString.Builder(source).apply {
        pattern.findAll(source.text).forEach { match ->
            if (urls.none { match.range.first <= it.last && match.range.last >= it.first }) {
                addStringAnnotation("project-file", candidates.getValue(match.value).value, match.range.first, match.range.last + 1)
            }
        }
    }.toAnnotatedString()
}

@Suppress("DEPRECATION")
@Composable
internal fun LinkedMessageText(text: String, files: List<ProjectPath>, modifier: Modifier = Modifier, openUrl: (String) -> Unit = {}, open: (ProjectPath) -> Unit) {
    val linked = remember(text, files.toList()) { messageFileLinks(markdownBold(text), files) }
    val background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val styled = AnnotatedString.Builder(linked).apply {
        Regex("https?://[^\\s<>\\\"']+").findAll(linked.text).forEach { match ->
            val url = match.value.trimEnd('.', ',', ')', ';')
            addStringAnnotation("preview-url", url, match.range.first, match.range.first + url.length)
            addStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF4D9F76), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), match.range.first, match.range.first + url.length)
        }
        linked.getStringAnnotations("project-file", 0, linked.length).forEach {
            addStyle(SpanStyle(background = background), it.start, it.end)
        }
    }.toAnnotatedString()
    ClickableText(styled, modifier = modifier, style = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 23.sp
    ), onClick = { offset ->
        styled.getStringAnnotations("preview-url", offset, offset).firstOrNull()?.let { openUrl(it.item); return@ClickableText }
        linked.getStringAnnotations("project-file", offset, offset).firstOrNull()?.let { annotation ->
            files.firstOrNull { it.value == annotation.item }?.let(open)
        }
    })
}
