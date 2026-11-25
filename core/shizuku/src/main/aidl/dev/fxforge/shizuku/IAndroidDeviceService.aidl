package dev.fxforge.shizuku;

import android.os.Bundle;

interface IAndroidDeviceService {
    void destroy() = 16777114;
    Bundle getDeviceInfo() = 1;
}
