package dev.aaa1115910.bv.tv.activities.dynamic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aaa1115910.bv.tv.screens.user.DynamicDetailScreen
import dev.aaa1115910.bv.ui.theme.BVTheme

class DynamicDetailActivity : ComponentActivity() {
    companion object {
        fun actionStart(context: Context, dynamicId: String) {
            context.startActivity(
                Intent(context, DynamicDetailActivity::class.java)
                    .putExtra(EXTRA_DYNAMIC_ID, dynamicId)
            )
        }

        const val EXTRA_DYNAMIC_ID = "dynamicId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BVTheme(forceDark = true) {
                DynamicDetailScreen(intent.getStringExtra(EXTRA_DYNAMIC_ID).orEmpty())
            }
        }
    }
}
