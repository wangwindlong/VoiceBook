package us.wangxy.voicebook.screens.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.epub.HtmlParser

/** 正文渲染：复用 EPUB 章节解析（标题层级 / 引用 / 列表 / 图片）。 */
@Composable
internal fun ArticleBlocks(html: String) {
    val blocks = remember(html) { HtmlParser.parse(html) }
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp)
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> {
                    val text = buildAnnotatedString {
                        for (span in block.spans) {
                            pushStyle(
                                SpanStyle(
                                    fontWeight = if (span.bold) FontWeight.Bold else null,
                                    fontStyle = if (span.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                                ),
                            )
                            append(span.text)
                            pop()
                        }
                    }
                    when {
                        block.headingLevel in 1..3 -> Text(
                            text = text,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = when (block.headingLevel) {
                                    1 -> 22.sp
                                    2 -> 19.sp
                                    else -> 17.sp
                                },
                            ),
                            fontWeight = FontWeight.Bold,
                        )

                        block.quote -> Column {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(4.dp)
                                    .background(Color.Transparent),
                            )
                            Text(text, style = body.copy(color = quoteColor))
                        }

                        else -> SelectionContainer { Text(text, style = body) }
                    }
                }

                is Block.Image -> AsyncImage(
                    model = block.src,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )

                is Block.Ruler -> HorizontalDivider()
            }
        }
    }
}
