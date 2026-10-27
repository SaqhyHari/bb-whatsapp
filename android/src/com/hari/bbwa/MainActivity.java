package com.hari.bbwa;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Launcher: opens the chat list when paired, otherwise asks for the server address and pairing code. */
public class MainActivity extends Activity {
    private static final int REQ_SCAN = 1;

    private EditText server;
    private EditText code;
    private EditText name;
    private TextView status;
    private Button go;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Api.paired(this)) {
            openList();
            return;
        }
        int p = UI.dp(this, 14);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(p, p, p, p);
        col.setBackgroundColor(UI.BG);

        col.addView(UI.text(this, "Pair with your bridge", 20, UI.GREEN, true), UI.wrap());
        col.addView(UI.text(this, "On the computer running the bridge, open http://localhost:3000/admin and press "
                + "Show pairing code. Then scan the square, or type the details below.", 13, UI.GREY, false), UI.wrap());

        Button scan = new Button(this);
        scan.setText("Scan QR code");
        scan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivityForResult(new Intent(MainActivity.this, ScanActivity.class), REQ_SCAN); }
        });
        col.addView(scan, UI.wrap());
        col.addView(UI.text(this, "or type the details:", 12, UI.GREY, false), UI.wrap());

        server = field("Server, e.g. 192.168.1.50:3000", InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(Api.base(this));
        code = field("6-digit pairing code", InputType.TYPE_CLASS_NUMBER);
        name = field("Device name", InputType.TYPE_CLASS_TEXT);
        name.setText("BlackBerry Q20");
        col.addView(label("Server address"), UI.wrap());
        col.addView(server, UI.wrap());
        col.addView(label("Pairing code"), UI.wrap());
        col.addView(code, UI.wrap());
        col.addView(label("This device is called"), UI.wrap());
        col.addView(name, UI.wrap());

        go = new Button(this);
        go.setText("Pair");
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pair(); }
        });
        col.addView(go, UI.wrap());
        status = UI.text(this, "", 14, Color.parseColor("#B00020"), false);
        col.addView(status, UI.wrap());

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UI.BG);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_SCAN || result != RESULT_OK || data == null || server == null) return;
        server.setText(data.getStringExtra("host"));
        code.setText(data.getStringExtra("code"));
        pair(); // the QR carried everything needed
    }

    private TextView label(String s) {
        TextView t = UI.text(this, s, 12, UI.GREY, false);
        t.setPadding(0, UI.dp(this, 8), 0, 0);
        return t;
    }

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(inputType);
        return e;
    }

    private void pair() {
        final String base = Api.normalize(server.getText().toString());
        final String c = code.getText().toString().trim();
        final String n = name.getText().toString().trim();
        if (base.length() < 8 || c.length() != 6) {
            status.setText("Enter the server address and the 6-digit code.");
            return;
        }
        go.setEnabled(false);
        status.setTextColor(UI.GREY);
        status.setText("Pairing...");
        Bg.go(new Bg.Job() {
            public Object run() throws Exception { return Api.pair(base, c, n.length() == 0 ? "BlackBerry" : n); }
        }, new Bg.Done() {
            public void done(Object result, Exception e) {
                if (isFinishing()) return;
                go.setEnabled(true);
                if (e != null) {
                    status.setTextColor(Color.parseColor("#B00020"));
                    status.setText(e instanceof Api.Err && ((Api.Err) e).code == 403
                            ? "Wrong or expired code. Show a new one on the computer." : Api.why(e));
                    return;
                }
                Api.sp(MainActivity.this).edit().putString("base", base).putString("token", (String) result)
                        .putLong("seq", -1).commit();
                startService(new Intent(MainActivity.this, PollService.class));
                openList();
            }
        });
    }

    private void openList() {
        startActivity(new Intent(this, ChatListActivity.class));
        finish();
    }
}
