package com.nmaclean.tvheadend

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    companion object {
        const val INPUT_ID = "com.nmaclean.tvheadend/.TvheadendInputService"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Automatically launch SetupActivity when opening the app launcher icon
        val intent = Intent(this, SetupActivity::class.java)
        startActivity(intent)
        finish()
    }
}
