package com.yeonjin.android.yjmediatrim;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Created by yeonjin.cho on 2018-05-14.
 */

public class VideoCodec extends Thread{
    private static String TAG = "[YJ] VideoCodec";

    private static final String MIME_TYPE = "video/avc";

    private static final int FRAME_RATE = 30;
    private static final int IFRAME_INTERVAL = 10;
    private static final int TIMEOUT_US = 10000;
    private static final float BPP = 0.25f;

    MediaCodec mDecoder = null, mEncoder = null;
    MediaCodec.BufferInfo mDecoderInfo = new MediaCodec.BufferInfo(), mEncoderInfo = new MediaCodec.BufferInfo();
    MediaExtractor mExtractor = null;
    MediaMuxer mMuxer = null;
    MediaFormat mOriginFormat = null, mNewFormat = null;

    String mMimeType = null;
    int mMuxerIndex = -1, mWidth = -1, mHeight = -1, mTrackIndex = -1;
    boolean mEos = false;

    public VideoCodec(MediaExtractor extractor, MediaMuxer muxer) {
        Log.d(TAG, "VideoCodec Create");
        //mExtractor = extractor;
        mMuxer = muxer;

        mExtractor = new MediaExtractor();
        try {
            mExtractor.setDataSource("/storage/emulated/0/YJRecorder/1.mp4");
        } catch (Exception e){
            e.printStackTrace();
        }
        mWidth = 720;
        mHeight = 480;

        for(int i=0; i<mExtractor.getTrackCount(); i++) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);

            if(mime.startsWith("video")) {
                mOriginFormat = format;
                mMimeType = mime;
                mTrackIndex = i;
                Log.d(TAG, "track index is " + i);
                mExtractor.selectTrack(mTrackIndex);
                break;
            }
        }
    }

    public int prepare() {
        Log.d(TAG, "VideoCodec prepare " + mMimeType);

        try {
            mDecoder = MediaCodec.createDecoderByType(mMimeType);
            mDecoder.configure(mOriginFormat, null, null, 0);
            mDecoder.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d(TAG, "Decoder started");

        mNewFormat = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
        mNewFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        mNewFormat.setInteger(MediaFormat.KEY_BIT_RATE, calcBitRate());
        mNewFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        mNewFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        try {
            mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
            mEncoder.configure(mNewFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoder.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d(TAG, "Encoder started");

        return mTrackIndex;
    }
    @Override
    public void run() {
        Log.d(TAG, "VideoCodec Thread started");

        while(true) {
            ByteBuffer decodedVideoBuffer = doVideoDecode();
            if(decodedVideoBuffer == null) {
                Log.e(TAG, "decoded buffer is not ready");
                continue;
            }
            doVideoEncode(decodedVideoBuffer);

            if(mEos) {
                Log.d(TAG, "Reached EOS");
                break;
            }
        }

        mDecoder.stop();
        mDecoder.release();
        mDecoder = null;

        mEncoder.stop();
        mEncoder.release();
        mEncoder = null;

        MediaTrimer.FinishSync++;
        Log.e(TAG, "end Video " + MediaTrimer.FinishSync);
    }

    public ByteBuffer doVideoDecode() {
        Log.d(TAG, "doVideoDecode");

        // Extractor에서 Encoded Data 를 읽어오는 부분 ---------------------------------------------------------------
        ByteBuffer decodedBuffer = null;
        int inputbufferIndex = mDecoder.dequeueInputBuffer(TIMEOUT_US);
        if (inputbufferIndex >= 0) {
            ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputbufferIndex);
            int sampleSize = mExtractor.readSampleData(inputBuffer, 0);

            if (sampleSize < 0) {
                Log.e(TAG, "sample size is 0");
                mDecoder.queueInputBuffer(inputbufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                mEos = true;
            } else {
                mDecoder.queueInputBuffer(inputbufferIndex, 0, sampleSize, mExtractor.getSampleTime(), 0);
            }
        }

        // Extractor 에서 읽어온 Encoded Data 를 Decoder 로 전달해주는 부분 ---------------------------------------------
        int outputbufferIndex = mDecoder.dequeueOutputBuffer(mDecoderInfo, TIMEOUT_US);
        if (outputbufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Log.d(TAG, "Decoder] INFO_OUTPUT_FORMAT_CHANGED");
        } else if (outputbufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            Log.d(TAG, "INFO_TRY_AGAIN_LATER");
            try {
                Thread.sleep(20);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (outputbufferIndex >= 0) {
            decodedBuffer = mDecoder.getOutputBuffer(outputbufferIndex);
            Log.d(TAG, "outputBufferIndex : " + outputbufferIndex + ", decoded Buffer size : " + decodedBuffer.capacity() + ", info : "+ mDecoderInfo);
            mDecoder.releaseOutputBuffer(outputbufferIndex, false);
        }
        if ((mDecoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.d(TAG, "Reached EOS");
            mEos = true;
        }

        return decodedBuffer;
    }

    public void doVideoEncode(ByteBuffer pcmBuffer) {
        Log.d(TAG, "doVideoEncode " + pcmBuffer.capacity());

        int position = 0;
        boolean end = false;
        pcmBuffer.clear();

        ByteBuffer tmpBuffer = ByteBuffer.allocateDirect(pcmBuffer.capacity());
        tmpBuffer.put(pcmBuffer);
        tmpBuffer.clear();

        while(true) {

            // 받은 pcm 데이터를 Encoder 로 넘겨주는 부분 ------------------------------------------------------------------------------------------
            int indexInputBuffer = mEncoder.dequeueInputBuffer(TIMEOUT_US);
            if (indexInputBuffer >= 0) {
                ByteBuffer inputBuffer = mEncoder.getInputBuffer(indexInputBuffer);
                inputBuffer.clear();
                tmpBuffer.position(position);

                if(tmpBuffer.capacity() - position <= inputBuffer.capacity()) {
                    Log.d(TAG, "all pcm buffer is encoded~");
                    tmpBuffer.limit(tmpBuffer.capacity());
                    inputBuffer.put(tmpBuffer.slice());
                    end = true;
                }
                else {
                    tmpBuffer.limit(inputBuffer.capacity() + position); // position : 0 ,limit : inputbuffer capa
                    inputBuffer.put(tmpBuffer.slice());
                }

                position += inputBuffer.capacity();

                if (mEos) {
                    mEncoder.queueInputBuffer(indexInputBuffer, 0, inputBuffer.capacity(), System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    mEncoder.queueInputBuffer(indexInputBuffer, 0, inputBuffer.capacity(), System.nanoTime() / 1000, 0);
                    mExtractor.advance();
                }
            }

            // 인코딩된 데이터를 받아서 파일에 써주는 부분 ---------------------------------------------------------------------------------------------------------
            int indexOutputBuffer = mEncoder.dequeueOutputBuffer(mEncoderInfo, TIMEOUT_US);
            Log.i(TAG, "dequeue output buffer audio index = " + indexOutputBuffer);
            if (indexOutputBuffer == MediaCodec.INFO_TRY_AGAIN_LATER) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else if (indexOutputBuffer == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mEncoder.getOutputFormat();
                mMuxerIndex = mMuxer.addTrack(newFormat);
                Log.e(TAG, "Encoder] INFO_OUTPUT_FORMAT_CHANGED " + newFormat.getString(MediaFormat.KEY_MIME) + ", MuxerIndex is " + mMuxerIndex + "MuxerSync " + MediaTrimer.MuxerSync + " >>>> MuxerStarted!");
                synchronized (MediaTrimer.MuxerLock) {
                    MediaTrimer.MuxerSync++;
                }
                if(MediaTrimer.MuxerSync == 2) {
                    synchronized (MediaTrimer.MuxerLock) {
                        MediaTrimer.MuxerLock.notifyAll();
                    }
                    mMuxer.start();
                }
                else {
                    synchronized (MediaTrimer.MuxerLock) {
                        try {
                            MediaTrimer.MuxerLock.wait();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

            } else if (indexOutputBuffer >= 0) {
                Log.d(TAG, "status " + indexOutputBuffer);
                ByteBuffer encodedData = mEncoder.getOutputBuffer(indexOutputBuffer);

                if ((mEncoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mEncoderInfo.size = 0;
                }

                if (mEncoderInfo.size != 0) {
                    encodedData.position(mEncoderInfo.offset);
                    encodedData.limit(mEncoderInfo.offset + mEncoderInfo.size);

                    mEncoderInfo.presentationTimeUs = System.nanoTime() / 1000;
                    Log.d(TAG,"writeSampleData "+ mEncoderInfo.size);
                    mMuxer.writeSampleData(mMuxerIndex, encodedData, mEncoderInfo);
                }
                mEncoder.releaseOutputBuffer(indexOutputBuffer, false);

                if ((mEncoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "end of audio stream");
                    mEos = true;
                    MediaTrimer.FinishSync++;
                    break;
                }
            }

            if(end)
                break;
        }

    }

    private int calcBitRate() {
        final int bitrate = (int)(BPP * FRAME_RATE * mWidth * mHeight);
        Log.i(TAG, "bitrate : " + bitrate);
        return bitrate;
    }
}
