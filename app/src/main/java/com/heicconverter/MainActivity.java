package com.heicconverter;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.Rotate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    private ArrayList<Uri> selectedImages = new ArrayList<>();
    private Button btnSelectImages;
    private Button btnConvert;
    private RadioGroup formatGroup;
    private TextView tvStatus;
    private ExecutorService executorService;

    private final ActivityResultLauncher<Intent> pickImages = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                selectedImages.clear();
                Intent data = result.getData();
                
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        Uri imageUri = data.getClipData().getItemAt(i).getUri();
                        selectedImages.add(imageUri);
                    }
                } else if (data.getData() != null) {
                    selectedImages.add(data.getData());
                }

                btnConvert.setEnabled(!selectedImages.isEmpty());
                tvStatus.setText("已选择 " + selectedImages.size() + " 张图片");
            }
        }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        
        initViews();
        setupListeners();
        checkPermissions();
    }

    private void initViews() {
        btnSelectImages = findViewById(R.id.btnSelectImages);
        btnConvert = findViewById(R.id.btnConvert);
        formatGroup = findViewById(R.id.formatGroup);
        tvStatus = findViewById(R.id.tvStatus);
        
        btnConvert.setEnabled(false);
    }

    private void setupListeners() {
        btnSelectImages.setOnClickListener(v -> selectImages());
        
        btnConvert.setOnClickListener(v -> {
            if (!selectedImages.isEmpty()) {
                convertImages();
            }
        });
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                        PERMISSION_REQUEST_CODE);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        },
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void selectImages() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/heic", "image/heif"});
        pickImages.launch(Intent.createChooser(intent, "选择HEIC图片"));
    }

    private void convertImages() {
        String format = formatGroup.getCheckedRadioButtonId() == R.id.rbJpg ? "jpg" : "jpeg";
        File outputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HeicConverter");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        btnConvert.setEnabled(false);
        btnSelectImages.setEnabled(false);
        int totalImages = selectedImages.size();
        int[] convertedCount = {0};

        for (Uri imageUri : selectedImages) {
            executorService.execute(() -> {
                try {
                    String fileName = "converted_" + System.currentTimeMillis() + "." + format;
                    File outputFile = new File(outputDir, fileName);

                    // 使用Glide加载和转换图片
                    Glide.with(this)
                            .asBitmap()
                            .load(imageUri)
                            .transform(new Rotate(0)) // 保持原始方向
                            .submit()
                            .get()
                            .compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, 
                                    new FileOutputStream(outputFile));

                    // 复制EXIF信息
                    copyExifData(imageUri, outputFile);

                    runOnUiThread(() -> {
                        convertedCount[0]++;
                        tvStatus.append("\n转换成功: " + fileName);
                        
                        // 添加到媒体库
                        MediaStore.Images.Media.insertImage(getContentResolver(),
                                outputFile.getAbsolutePath(), fileName, null);
                        
                        // 检查是否所有图片都已转换
                        if (convertedCount[0] >= totalImages) {
                            btnConvert.setEnabled(true);
                            btnSelectImages.setEnabled(true);
                            Toast.makeText(MainActivity.this, "所有转换任务已完成", 
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        convertedCount[0]++;
                        tvStatus.append("\n转换失败: " + e.getMessage());
                        
                        // 检查是否所有图片都已处理
                        if (convertedCount[0] >= totalImages) {
                            btnConvert.setEnabled(true);
                            btnSelectImages.setEnabled(true);
                        }
                    });
                }
            });
        }
    }

    private void copyExifData(Uri sourceUri, File destFile) {
        try {
            ExifInterface sourceExif = new ExifInterface(getContentResolver().openInputStream(sourceUri));
            ExifInterface destExif = new ExifInterface(destFile.getAbsolutePath());

            // 复制所有可用的EXIF标签
            String[] attributes = new String[]{
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_FLASH,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_PROCESSING_METHOD,
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_IMAGE_LENGTH,
                    ExifInterface.TAG_IMAGE_WIDTH,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.TAG_WHITE_BALANCE
            };

            for (String attribute : attributes) {
                String value = sourceExif.getAttribute(attribute);
                if (value != null) {
                    destExif.setAttribute(attribute, value);
                }
            }
            destExif.saveAttributes();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "需要存储权限才能继续操作", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}