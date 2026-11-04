package com.vietassist.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/** Debug-only controlled speech source for instrumented recognition tests. */
public class TestCommandRecognitionService extends RecognitionService {
    public static boolean delivered;
    private int generation;
    @Override protected void onStartListening(Intent intent, Callback callback) {
        final int request = ++generation;
        try {
            callback.readyForSpeech(new Bundle());
            if (!delivered) {
                delivered = true;
                callback.beginningOfSpeech();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (request != generation) return;
                    Bundle result = new Bundle();
                    ArrayList<String> matches = new ArrayList<>();
                    matches.add("đọc văn bản");
                    result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, matches);
                    try {
                        if (intent.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)) callback.partialResults(result);
                    } catch (android.os.RemoteException ignored) { }
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try { callback.results(result); } catch (android.os.RemoteException ignored) { }
                    }, 8000);
                }, 700);
            } else callback.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
        } catch (android.os.RemoteException ignored) { }
    }
    @Override protected void onStopListening(Callback callback) { generation++; }
    @Override protected void onCancel(Callback callback) { generation++; }
}
