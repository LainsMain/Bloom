package be.lain.bloom.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import be.lain.bloom.core.designsystem.EmptyState

@Composable
fun ComingSoonScreen(title: String, body: String, icon: ImageVector) {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        EmptyState(MaterialShapes.Flower, icon, title, body)
    }
}
