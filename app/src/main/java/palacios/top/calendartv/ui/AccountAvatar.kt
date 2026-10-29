package palacios.top.calendartv.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import palacios.top.calendartv.banner.AvatarStyle
import palacios.top.calendartv.data.local.AccountEntity

/** The account's Google photo as a circle; until the photo permission is granted, a colored circle with its initial. */
@Composable
fun AccountAvatar(account: AccountEntity?, size: Dp = 40.dp) {
    if (account == null) return
    val bitmap = remember(account.photoPath) {
        account.photoPath.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = account.email,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        val fill = runCatching { Color(android.graphics.Color.parseColor(AvatarStyle.color(account.email))) }
            .getOrDefault(Color.Gray)
        Box(Modifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
            Text(AvatarStyle.initial(account.displayName, account.email), color = Color.White, fontSize = (size.value * 0.45f).sp)
        }
    }
}
