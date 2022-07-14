package com.yeonjin.android.yjmediatrim;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.Lock;

/**
 * Created by yeonjin.cho on 2018-05-12.
 */

public class MediaTrimer {
    private static String TAG = "[YJ] MediaTrimer";

    public static int MuxerSync = 0;
    public static Object MuxerLock = new Object();
    public static int FinishSync = 0;

    MediaExtractor mExtractor = null;
    MediaMuxer mMuxer = null;

    VideoCodec mVideoCodec = null;
    AudioCodec mAudioCodec = null;

    int mAudioIndex = -1, mVideoIndex = -1;

    public MediaTrimer(String filepath){
        Log.d(TAG, "MediaTrimer created " + filepath);
        mExtractor = new MediaExtractor();
        try {
            mExtractor.setDataSource("/storage/emulated/0/YJRecorder/1.mp4");
        } catch (Exception e){
            e.printStackTrace();
        }

        try {
            mMuxer = new MediaMuxer("/storage/emulated/0/YJRecorder/test.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch(Exception e) {
            e.printStackTrace();
        }

        mVideoCodec = new VideoCodec(mExtractor, mMuxer);
        mAudioCodec = new AudioCodec(mExtractor, mMuxer);
    }



    public void startTrim(long startUs, long endUs) {
        Log.d(TAG, "startTrim start : " + startUs + ", end : " + endUs);
        mExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        //mVideoIndex = mVideoCodec.prepare();
        mAudioIndex = mAudioCodec.prepare();

        //mVideoCodec.start();
        mAudioCodec.start();

        while (true) {
            if (FinishSync == 1) {
                Log.d(TAG, "Reached EOS");
                break;
            }
        }
        mExtractor.release();
        mMuxer.stop();
        mMuxer.release();
    }
}
