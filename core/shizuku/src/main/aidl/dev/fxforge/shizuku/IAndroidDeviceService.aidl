package dev.fxforge.shizuku;

import android.os.ParcelFileDescriptor;
import android.os.Bundle;

interface IAndroidDeviceService {
    void destroy() = 16777114;
    Bundle getDeviceInfo() = 1;
    Bundle readLogcat(int maxLines) = 2;
    Bundle installApk(in ParcelFileDescriptor apkDescriptor) = 3;
    Bundle executeCommand(String command, int timeoutSeconds) = 4;
}
