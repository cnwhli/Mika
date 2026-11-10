package com.nuoyi.agent.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.nuoyi.agent.R
import com.nuoyi.agent.ui.theme.rememberMascotBitmap

/** Nuoyi 的标志:圆角方块里的吉祥物。圆角默认按边长的 0.28(36dp 约 10,76dp 约 22)。 */
@Composable
fun NuoyiLogo(size: Dp, modifier: Modifier = Modifier, corner: Dp = size * 0.28f) {
    val custom = rememberMascotBitmap()
    Image(
        painter = custom?.let { BitmapPainter(it) } ?: painterResource(R.drawable.ic_mascot),
        contentDescription = null,
        modifier = modifier.size(size).clip(RoundedCornerShape(corner)),
        contentScale = ContentScale.Fit,
    )
}
