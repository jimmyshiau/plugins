// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.imagepicker;

import android.os.Build;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.Manifest;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.content.pm.PackageManager;

import com.esafirm.imagepicker.features.ImagePicker;
import com.esafirm.imagepicker.features.camera.DefaultCameraModule;
import com.esafirm.imagepicker.features.camera.OnImageReadyListener;
import com.esafirm.imagepicker.model.Image;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener;

import java.io.InputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Location Plugin */
public class ImagePickerPlugin implements MethodCallHandler, ActivityResultListener {
//  private static String TAG = "flutter";
  private static final String CHANNEL = "image_picker";

  private static final int REQUEST_CODE_PICK = 2342;
  private static final int REQUEST_CODE_CAMERA = 2343;

  private static final int SOURCE_ASK_USER = 0;
  private static final int SOURCE_CAMERA = 1;
  private static final int SOURCE_GALLERY = 2;

  private static final DefaultCameraModule cameraModule = new DefaultCameraModule();

  private final PluginRegistry.Registrar registrar;

  // Pending method call to obtain an image
  private Result pendingResult;
  private MethodCall methodCall;

  public static void registerWith(PluginRegistry.Registrar registrar) {
    final MethodChannel channel = new MethodChannel(registrar.messenger(), CHANNEL);
    final ImagePickerPlugin instance = new ImagePickerPlugin(registrar);
    registrar.addActivityResultListener(instance);
    channel.setMethodCallHandler(instance);
  }

  private ImagePickerPlugin(PluginRegistry.Registrar registrar) {
    this.registrar = registrar;
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    if (pendingResult != null) {
      result.error("ALREADY_ACTIVE", "Image picker is already active", null);
      return;
    }

    Activity activity = registrar.activity();
    if (activity == null) {
      result.error("no_activity", "image_picker plugin requires a foreground activity.", null);
      return;
    }

    pendingResult = result;
    methodCall = call;

    if (call.method.equals("pickImage")) {
      int imageSource = call.argument("source");

      switch (imageSource) {
        case SOURCE_ASK_USER:
          ImagePicker.create(activity).single().start(REQUEST_CODE_PICK);
          break;
        case SOURCE_GALLERY:
          ImagePicker.create(activity).single().theme(R.style.ImagePickerTheme).showCamera(false).start(REQUEST_CODE_PICK);
          break;
        case SOURCE_CAMERA:
          boolean cameraPermission = hasPermission(Manifest.permission.CAMERA),
                  photoPermission = hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE);

          String[] permissions = photoPermission ? !cameraPermission ? new String[]{Manifest.permission.CAMERA}: null:
                  !cameraPermission ? new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE}:
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
          if (permissions != null) {
            askPermission(permissions);
            pendingResult = null;
            return;
          }
         activity.startActivityForResult(
             cameraModule.getCameraIntent(activity), REQUEST_CODE_CAMERA);
          break;
        default:
          throw new IllegalArgumentException("Invalid image source: " + imageSource);
      }
    } else {
      throw new IllegalArgumentException("Unknown method " + call.method);
    }
  }

  private boolean hasPermission(String permission) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
      return true;

    int _hasPermission = ContextCompat.checkSelfPermission(registrar.activity(), permission);
    return _hasPermission == PackageManager.PERMISSION_GRANTED;
  }

  private void askPermission(String[] permissions) {
    ActivityCompat.requestPermissions(registrar.activity(), permissions, REQUEST_CODE_CAMERA);
  }

  @Override
  public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == REQUEST_CODE_PICK) {
      if (resultCode == Activity.RESULT_OK && data != null) {
        ArrayList<Image> images = (ArrayList<Image>) ImagePicker.getImages(data);
        handleResult(images.get(0));
        return true;
      } else if (resultCode != Activity.RESULT_CANCELED) {
        pendingResult.error("PICK_ERROR", "Error picking image", null);
      }

      pendingResult = null;
      methodCall = null;
      return true;
    }
    if (requestCode == REQUEST_CODE_CAMERA) {
      if (resultCode == Activity.RESULT_OK) {
        cameraModule.getImage(
            registrar.context(),
            data,
            new OnImageReadyListener() {
              @Override
              public void onImageReady(List<Image> images) {
                handleResult(images.get(0));
              }
            });
        return true;
      } else if (resultCode != Activity.RESULT_CANCELED) {
        pendingResult.error("PICK_ERROR", "Error taking photo", null);
      }

      pendingResult = null;
      methodCall = null;
      return true;
    }
    return false;
  }

  private void handleResult(Image image) {
    if (pendingResult != null) {
      Double maxWidth = methodCall.argument("maxWidth");
      Double maxHeight = methodCall.argument("maxHeight");
      Integer quality = methodCall.argument("quality");
      
      boolean shouldScale = maxWidth != null || maxHeight != null || quality != null;

      try {
        byte[] bytes = shouldScale ? scaleImage(image, maxWidth, maxHeight, quality) : read(image.getPath());
        ArrayList<Object> result = new ArrayList<>();
        result.add(image.getName());
        result.add(bytes);
        pendingResult.success(result);
      } catch (IOException e) {
        throw new RuntimeException(e);
      } finally {
        pendingResult = null;
        methodCall = null;
      }
    } else {
      throw new IllegalStateException("Received images from picker that were not requested");
    }
  }

  private byte[] read(String path) throws IOException {
    ByteArrayOutputStream ous = null;
    InputStream ios = null;
    try {
      byte[] buffer = new byte[4096];
      ous = new ByteArrayOutputStream();
      ios = new FileInputStream(path);
      int read;
      while ((read = ios.read(buffer)) != -1) {
        ous.write(buffer, 0, read);
      }
    } finally {
      try {
        if (ous != null)
          ous.close();
      } catch (IOException e) {
        // do nothing
      }

      try {
        if (ios != null)
          ios.close();
      } catch (IOException e) {
        // do nothing
      }
    }
    return ous.toByteArray();
  }

  private byte[] scaleImage(Image image, Double maxWidth, Double maxHeight, Integer quality) throws IOException {
    Bitmap bmp = BitmapFactory.decodeFile(image.getPath());
    double originalWidth = bmp.getWidth() * 1.0;
    double originalHeight = bmp.getHeight() * 1.0;

    boolean hasMaxWidth = maxWidth != null;
    boolean hasMaxHeight = maxHeight != null;

    Double width = hasMaxWidth ? Math.min(originalWidth, maxWidth) : originalWidth;
    Double height = hasMaxHeight ? Math.min(originalHeight, maxHeight) : originalHeight;

    boolean shouldDownscaleWidth = hasMaxWidth && maxWidth < originalWidth;
    boolean shouldDownscaleHeight = hasMaxHeight && maxHeight < originalHeight;
    boolean shouldDownscale = shouldDownscaleWidth || shouldDownscaleHeight;

    if (shouldDownscale) {
      double downscaledWidth = (height / originalHeight) * originalWidth;
      double downscaledHeight = (width / originalWidth) * originalHeight;

      if (width < height) {
        if (!hasMaxWidth) {
          width = downscaledWidth;
        } else {
          height = downscaledHeight;
        }
      } else if (height < width) {
        if (!hasMaxHeight) {
          height = downscaledHeight;
        } else {
          width = downscaledWidth;
        }
      } else {
        if (originalWidth < originalHeight) {
          width = downscaledWidth;
        } else if (originalHeight < originalWidth) {
          height = downscaledHeight;
        }
      }
    }

    Bitmap scaledBmp = Bitmap.createScaledBitmap(bmp, width.intValue(), height.intValue(), false);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    scaledBmp.compress(Bitmap.CompressFormat.JPEG, quality != null ? quality: 100, outputStream);

    return outputStream.toByteArray();
  }
}
