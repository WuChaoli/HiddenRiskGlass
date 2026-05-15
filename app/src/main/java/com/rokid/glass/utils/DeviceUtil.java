package com.rokid.glass.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.icu.text.SimpleDateFormat;
import android.os.Build;
import android.os.LocaleList;
import android.os.PowerManager;
import android.provider.Settings;

import com.rokid.security.glass3.open.sdk.uitls.log.L;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Date;

/**
 * 设备信息 工具类
 *
 * @author : liuweiming
 * @date : 2021/7/7
 */
public class DeviceUtil {

    public static final String TAG = DeviceUtil.class.getSimpleName();

    /*系统信息
     * [0] 内核版本
     * [1] 系统版本
     * [2] 手机型号
     * [3] 固件版本
     * [4] 品牌
     * */
    public static String[] getVersion() throws Exception{
        String[] version={"null","null","null","null","null"};
        String str1 = "/proc/version";
        String str2;
        String[] arrayOfString;
        try {
            FileReader localFileReader = new FileReader(str1);
            BufferedReader localBufferedReader = new BufferedReader(
                    localFileReader, 8192);
            str2 = localBufferedReader.readLine();
            arrayOfString = str2.split("\\s+");
            version[0]=arrayOfString[2];//KernelVersion
            localBufferedReader.close();
        } catch (IOException e) {
        }
        version[1] = Build.VERSION.RELEASE;// system version
        version[2] = Build.MODEL;//model
        version[3] = Build.DISPLAY;//firmware version
        version[4] = Build.BRAND;//ping pai
        return version;
    }

    public static String getVersionName(Context context)
    {
        // 获取packagemanager的实例
        PackageManager packageManager = context.getPackageManager();
        // getPackageName()是你当前类的包名，0代表是获取版本信息
        PackageInfo packInfo = null;
        try {
            packInfo = packageManager.getPackageInfo(context.getPackageName(),0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        String version = packInfo.versionName;
        return version;
    }

    public static String getVersionCode(Context context)
    {
        // 获取packagemanager的实例
        PackageManager packageManager = context.getPackageManager();
        // getPackageName()是你当前类的包名，0代表是获取版本信息
        PackageInfo packInfo = null;
        try {
            packInfo = packageManager.getPackageInfo(context.getPackageName(),0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        int version = packInfo.versionCode;
        return version+"";
    }

    /**
     * 获得其中的当前版本
     * @return
     */
    public static String getSystemVersion() {
        try {
            String[] strBuildInfoArray = Build.FINGERPRINT.split(":");
            String[] strBuildNumArray = strBuildInfoArray[1].split("/");
            String buildNumber = strBuildNumArray[strBuildNumArray.length - 1];
            if (!isSpriteDisplaySys()) {
                buildNumber = buildNumber.replace("-150", "-151");
            }
            return buildNumber;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 获得当前的三位数字版本
     * @return
     */
    public static String getCurrentThreeDigitVersion() {
        try {
            String[] numberText = getSystemVersion().split("-");
            return numberText[0];
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }


    /**
     * 获取手机型号
     *
     * @return  手机型号
     */
    public static String getSystemModel() {
        return Build.MODEL;
    }

    /**
     * 获取手机厂商
     *
     * @return  手机厂商
     */
    public static String getDeviceBrand() {
        return Build.BRAND;
    }

    public static String getNowDataTime() {
        SimpleDateFormat sdf= new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", LocaleList.getDefault().get(0));
        Date dt = new Date(System.currentTimeMillis());
        return sdf.format(dt);
    }

    public static boolean isHallMode() {
        return "1".equals(getSystemProp("persist.vendor.rkd.ui_mode"));
    }

    public static String getSn() {
        return getSystemProp("ro.serialno");
    }

    public static String getDeviceType() {
        return getSystemProp("ro.boot.devicetypeid");
    }

    public static void setSystemProp(String key, String value) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method set = c.getMethod("set", String.class, String.class);
            set.invoke(c, key, value);
            L.d(TAG, "setProp " + key + "、" + value);
        } catch (Exception e) {
            L.e(TAG, "setSystemProp [" + key + "]:[" + value + "] error!");
        }
    }
    public static String getSystemProp(String key) {
        String value = null;

        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method method = systemProperties.getMethod("get", String.class);
            value = (String) method.invoke(null, key);
        } catch (Exception e) {
            e.printStackTrace();
        }
//        L.d(TAG,"Prop " + key + "、" + value);
        return value;
    }

    public static void setGlassMode(boolean is3D){
        L.d(TAG,"setPlayMode call --> " + (is3D ? "3d" : "2d"));

        try {
            Class aClass = Class.forName("com.rokid.display.RokidDisplayManager");
            Method instanceMethod = aClass.getDeclaredMethod("instance");
            Object o = instanceMethod.invoke(null);
            Method setAirDisplayMode = aClass.getDeclaredMethod("setAirDisplayMode", int.class);
            setAirDisplayMode.invoke(o, is3D ? 1 : 0);
            L.d(TAG,"==set2DMode  success --> " + (is3D ? "3d" : "2d"));

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }


    public static String getGlassDeviceName(Context context) {
        String deviceName = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
        if (deviceName == null) {
            deviceName = Build.MODEL;
        }
        return deviceName;
    }


    public static boolean isGlassTackOn() {
        return "1".equals(getSystemProp("vendor.rkd.glasses.is_take_on"));
    }

    public static boolean isGlassLegFold() {
        return "0".equals(getSystemProp("vendor.rkd.glasses.is_spread"));
    }

    public static boolean isSpriteDisplaySys() {
        return  "1".equals(getSystemProp("ro.boot.glassesWithPanel"));
    }


    public static void rebootDevice(Context context) {
        L.v("DeviceUtil", "rebootDevice() call");
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                L.v("DeviceUtil", "the powerManager reboot call");
                powerManager.reboot(null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
