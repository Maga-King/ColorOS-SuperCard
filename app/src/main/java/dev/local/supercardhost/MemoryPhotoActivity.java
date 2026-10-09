package dev.local.supercardhost;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

/** Uses the installed camera's real image-capture result, including cancellation. */
public final class MemoryPhotoActivity extends Activity {
    private Uri output;
    private String token;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        output = getIntent().getData(); token = getIntent().getStringExtra("token");
        if (output == null || !MemoryTransferProvider.AUTHORITY.equals(output.getAuthority())
                || token == null || !token.matches("[0-9a-f-]{36}")) { finish(); return; }
        if (state != null) return;
        try {
            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, output)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            camera.setClipData(ClipData.newRawUri("记忆照片", output));
            camera.setPackage("com.oplus.camera");
            if (camera.resolveActivity(getPackageManager()) == null) {
                camera.setPackage("com.android.camera");
            }
            startActivityForResult(camera, 101);
        } catch (Exception error) {
            Toast.makeText(this, "无法打开相机：" + error.getMessage(), Toast.LENGTH_LONG).show();
            result(false);
        }
    }
    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request == 101) result(code == RESULT_OK);
    }
    private void result(boolean success) {
        BroadcastOptions options = BroadcastOptions.makeBasic(); options.setShareIdentityEnabled(true);
        sendBroadcast(new Intent(MemoryCaptureSupport.RESULT).setPackage("com.android.systemui")
                .putExtra("token", token).putExtra("success", success), null, options.toBundle());
        finish();
    }
}
