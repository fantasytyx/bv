package dev.aaa1115910.bv.tv.activities.user

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aaa1115910.bv.tv.screens.user.ArticleDetailScreen
import dev.aaa1115910.bv.ui.theme.BVTheme

class ArticleActivity : ComponentActivity() {
    companion object {
        fun actionStart(context: Context, articleId: Long) {
            context.startActivity(
                Intent(context, ArticleActivity::class.java)
                    .putExtra(EXTRA_ARTICLE_ID, articleId)
            )
        }

        const val EXTRA_ARTICLE_ID = "articleId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val articleId = intent.getLongExtra(EXTRA_ARTICLE_ID, 0L)
        setContent {
            BVTheme(forceDark = true) {
                ArticleDetailScreen(articleId)
            }
        }
    }
}
