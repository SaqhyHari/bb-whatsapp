package com.hari.bbwa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Api.paired(c)) c.startService(new Intent(c, PollService.class));
    }
}
