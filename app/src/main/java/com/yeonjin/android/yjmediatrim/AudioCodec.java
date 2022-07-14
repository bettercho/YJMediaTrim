package com.yeonjin.android.yjmediatrim;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * Created by yeonjin.cho on 2018-05-12.
 */

public class AudioCodec extends Thread{
    private static String TAG = "[YJ] AudioCodec";
    private static final int TIMEOUT_US = 10000;

    String MIME_TYPE = "audio/amr-wb";
    int CHANNEL_COUNT = 1;
    int SAMPLE_RATE = 44100;
    int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
    int BIT_PER_SAMPLE = AudioFormat.ENCODING_PCM_16BIT;
    int BIT_RATE = 128000;

    MediaCodec mDecoder = null, mEncoder = null;
    MediaCodec.BufferInfo mDecoderInfo = new MediaCodec.BufferInfo(), mEncoderInfo = new MediaCodec.BufferInfo();
    MediaExtractor mExtractor = null;
    MediaMuxer mMuxer = null;
    MediaFormat mOriginFormat = null, mNewFormat = null;

    String mMimeType = null;
    int mMuxerIndex = -1, mTrackIndex = -1;
    boolean mEos = false;

    public AudioCodec(MediaExtractor extractor, MediaMuxer muxer) {
        Log.d(TAG, "AudioCodec Create");
        //mExtractor = extractor;
        mMuxer = muxer;

        mExtractor = new MediaExtractor();
        try {
            mExtractor.setDataSource("/storage/emulated/0/YJRecorder/1.mp4");
        } catch (Exception e){
            e.printStackTrace();
        }


        for(int i=0; i<mExtractor.getTrackCount(); i++) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            Log.d(TAG, "format : " + format + ", mime " +mime);
            if(mime.startsWith("audio")) {
                mOriginFormat = format;
                mMimeType = mime;
                mTrackIndex = i;
                mExtractor.selectTrack(mTrackIndex);
                Log.d(TAG, "track index is " + i);
                break;
            }
        }
    }

    public int prepare() {
        Log.d(TAG, "AudioCodec prepare " + mMimeType);

        try {
            mDecoder = MediaCodec.createDecoderByType(mMimeType);
            mDecoder.configure(mOriginFormat, null, null, 0);
            mDecoder.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d(TAG, "Decoder started");

        mNewFormat = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT);
        mNewFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, CHANNEL_MASK);
        mNewFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE );
        mNewFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNEL_COUNT);

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
        Log.d(TAG, "AudioCodec Thread started");

        while(true) {
            ByteBuffer decodedAudioBuffer = doAudioDecode();
            if(decodedAudioBuffer == null) {
                Log.e(TAG, "decoded buffer is not ready");
                continue;
            }
           doAudioEncode(decodedAudioBuffer);

            if(mEos) {
                Log.d(TAG, "Reached EOS");
                MediaTrimer.FinishSync++;
                break;
            }
        }

        mDecoder.stop();
        mDecoder.release();;
        mDecoder = null;

        mEncoder.stop();
        mEncoder.release();
        mEncoder = null;

        MediaTrimer.FinishSync++;
        Log.e(TAG, "end AudioCodec " + MediaTrimer.FinishSync);
    }

    // PCM 데이터를 return 하는 함수
    public ByteBuffer doAudioDecode() {
        Log.d(TAG, "doAudioDecode");

        // Extractor에서 Encoded Data 를 읽어와 Decoder 로 전달해 주는 부분 ---------------------------------------------------------------
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
                mExtractor.advance();
            }
        }

        // 디코딩된 버퍼를 가져오는 부분  ---------------------------------------------
        int outputbufferIndex = mDecoder.dequeueOutputBuffer(mDecoderInfo, TIMEOUT_US);
        if (outputbufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Log.e(TAG, "Decoder] INFO_OUTPUT_FORMAT_CHANGED " + mDecoderInfo);
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
            /*final byte[] byte_buffer = new byte[mDecoderInfo.size];
            decodedBuffer.get(byte_buffer);
            decodedBuffer.clear();
            try {
                mFOS.write(byte_buffer);
            } catch (Exception e) {
                e.printStackTrace();
            }*/ // Decoded buffer 는 아주 좋음.
            mDecoder.releaseOutputBuffer(outputbufferIndex, false);
        }

        if ((mDecoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.d(TAG, "Reached EOS");
            mEos = true;
        }

        return decodedBuffer;
    }

    // 받은 PCM 데이터를 인코딩하여 File 에 쓰는 함수
    public void doAudioEncode(ByteBuffer pcmBuffer) {
        Log.d(TAG, "doAudioEncode " + pcmBuffer.capacity());

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

                Log.d(TAG, "position : " + position +" inputBuffer capa " + inputBuffer.capacity());
                tmpBuffer.position(position);

                if(tmpBuffer.capacity() - position <= inputBuffer.capacity()) {
                    Log.d(TAG, "all pcm buffer is encoded");
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
                Log.e(TAG, "Encoder] INFO_OUTPUT_FORMAT_CHANGED " + newFormat.getString(MediaFormat.KEY_MIME) + ", MuxerIndex is "+ mMuxerIndex +"MuxerSync " + MediaTrimer.MuxerSync + " >>>> MuxerStarted!");
                mMuxer.start();
            } else if (indexOutputBuffer >= 0) {
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
                    break;
                }
            }
            if(end)
                break;
        }
    }
}
