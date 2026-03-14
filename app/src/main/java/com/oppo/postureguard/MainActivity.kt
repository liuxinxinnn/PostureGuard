package com.oppo.postureguard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.oppo.postureguard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startButton.setOnClickListener {
            openImmersive(isQuickTest = false)
        }

        binding.quickTest.setOnClickListener {
            openImmersive(isQuickTest = true)
        }
    }

    private fun openImmersive(isQuickTest: Boolean) {
        val intent = Intent(this, ImmersiveActivity::class.java).apply {
            putExtra(ImmersiveActivity.EXTRA_HUNCH, binding.switchHunch.isChecked)
            putExtra(ImmersiveActivity.EXTRA_HEAD, binding.switchHead.isChecked)
            putExtra(ImmersiveActivity.EXTRA_WATER, binding.switchWater.isChecked)
            putExtra(ImmersiveActivity.EXTRA_QUICK_TEST, isQuickTest)
        }
        startActivity(intent)
    }
}
