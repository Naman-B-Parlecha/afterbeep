package dev.vm.route5;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setPadding(48, 160, 48, 48);
        t.setTextSize(18);
        t.setText("VM Route5 spike running.\nIncoming calls will be auto-answered.\nKill the app to stop.");
        setContentView(t);
        requestPermissions(new String[]{
                "android.permission.READ_PHONE_STATE", "android.permission.ANSWER_PHONE_CALLS",
                "android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"}, 1);
        if (checkSelfPermission("android.permission.RECORD_AUDIO") == 0) arm();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (checkSelfPermission("android.permission.RECORD_AUDIO") == 0) arm();
    }

    private void arm() {
        startForegroundService(new Intent(this, VmService.class)); // keeps a mic-type FGS alive
        android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
        if (!rm.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER))
            startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER), 2);
    }
}
