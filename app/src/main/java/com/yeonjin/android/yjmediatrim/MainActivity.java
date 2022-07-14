package com.yeonjin.android.yjmediatrim;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

public class MainActivity extends Activity {
    private static String TAG = "[YJ] MainActivity";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate()");
    }

    public void onClickStartButton (View view) {
        Log.d(TAG,"startButton Clicked");
        MediaTrimer trimer  = new MediaTrimer("yeonjin test trim");
        trimer.startTrim(70000000, 110000000);

    }
}
