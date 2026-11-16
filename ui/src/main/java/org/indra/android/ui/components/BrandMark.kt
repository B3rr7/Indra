package org.indra.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import org.indra.android.ui.R

/**
 * The Indra mark: the peacock-feather mandala, in place of the shield iconography this app used to
 * carry.
 *
 * Two sizes of the same artwork, because one does not work for both jobs. At a top bar's 28 dp the
 * full fan of twenty-two feathers is a spray of dots, so small spaces take [BrandGlyph] — the
 * fan's central feather, which still reads as an emblem; the full fan is a signature block, so
 * [BrandMark] is for the footer and any other place with room for it.
 *
 * Both are white ink on transparent: the source image's black page is converted to alpha when the
 * asset is produced, so the mark sits on any palette's page colour with no plate behind it and
 * needs no tint. [ContentScale.Fit] keeps each one's aspect ratio at whatever size the caller asks
 * for.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(R.drawable.brand_mark),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** The fan's central feather: the mark for spaces too small for the whole fan. See [BrandMark]. */
@Composable
fun BrandGlyph(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(R.drawable.brand_mark_small),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
