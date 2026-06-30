package com.aicompanion.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class LicenseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewUtils.setupEdgeToEdge(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.background))
        }

        // 标题栏
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dip(16), dip(12), dip(16), dip(12))
            setBackgroundResource(R.drawable.bg_sakura_header)
        }
        val btnBack = TextView(this).apply {
            text = getString(R.string.license_back_label)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_warm))
            setPadding(0, 0, dip(16), 0)
            setOnClickListener { finish() }
        }
        titleBar.addView(btnBack)
        titleBar.addView(TextView(this).apply {
            text = getString(R.string.license_title)
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.wb_text_warm))
            paint.isFakeBoldText = true
        })
        rootLayout.addView(titleBar)

        // 分隔线
        rootLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dip(1)
            )
            setBackgroundColor(ContextCompat.getColor(context, R.color.glass_border))
        })

        // 滚动内容
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(16), dip(16), dip(16), dip(16))
        }

        val libraries = listOf(
            LicenseInfo(
                "Chaquopy",
                "MIT",
                getString(R.string.license_chaquopy_desc),
                "https://chaquo.com/chaquopy/"
            ),
            LicenseInfo(
                "sherpa-onnx",
                "Apache 2.0",
                getString(R.string.license_sherpa_onnx_desc),
                "https://github.com/k2-fsa/sherpa-onnx"
            ),
            LicenseInfo(
                "AndroidX",
                "Apache 2.0",
                getString(R.string.license_androidx_desc),
                "https://developer.android.com/jetpack/androidx"
            ),
            LicenseInfo(
                "Material Components",
                "Apache 2.0",
                getString(R.string.license_material_desc),
                "https://github.com/material-components/material-components-android"
            ),
            LicenseInfo(
                "Kotlin",
                "Apache 2.0",
                getString(R.string.license_kotlin_desc),
                "https://kotlinlang.org/"
            ),
            LicenseInfo(
                "DeepSeek API",
                "商用许可",
                getString(R.string.license_deepseek_desc),
                "https://platform.deepseek.com/"
            ),
            LicenseInfo(
                "wttr.in",
                "免费服务",
                getString(R.string.license_wttr_in_desc),
                "https://wttr.in/"
            )
        )

        for (lib in libraries) {
            contentLayout.addView(createLicenseCard(lib))
        }

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)
    }

    private fun createLicenseCard(lib: LicenseInfo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glass_card)
            setPadding(dip(16), dip(14), dip(16), dip(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dip(10)
            }
        }

        // 名称 + 许可证类型
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = lib.name
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = lib.license
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            setBackgroundResource(R.drawable.bg_chip_rounded)
            setPadding(dip(8), dip(2), dip(8), dip(2))
        })
        card.addView(headerRow)

        // 说明
        card.addView(TextView(this).apply {
            text = lib.description
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dip(6), 0, dip(4))
        })

        // 链接
        card.addView(TextView(this).apply {
            text = lib.url
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        })

        return card
    }

    private fun dip(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private data class LicenseInfo(
        val name: String,
        val license: String,
        val description: String,
        val url: String
    )
}