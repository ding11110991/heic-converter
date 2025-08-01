package com.heicconverter;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.Rotate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    private ArrayList<Uri> selectedImages = new ArrayList<>();
    private Button btnSelectImages;
    private Button btnSelectAllHeic;
    private Button btnConvert;
    private RadioGroup formatGroup;
    private TextView tvStatus;
    private TextView tvImageCount;
    private TextView tvProgress;
    private RecyclerView rvImagePreview;
    private ImagePreviewAdapter imagePreviewAdapter;
    private ExecutorService executorService;
    private int successCount = 0;

    private final ActivityResultLauncher<Intent> pickImages = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                selectedImages.clear();
                Intent data = result.getData();
                
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        selectedImages.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    selectedImages.add(data.getData());
                }
                updateImageSelection();
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
        btnSelectAllHeic = findViewById(R.id.btnSelectAllHeic);
        btnConvert = findViewById(R.id.btnConvert);
        formatGroup = findViewById(R.id.formatGroup);
        tvStatus = findViewById(R.id.tvStatus);
        tvImageCount = findViewById(R.id.tvImageCount);
        tvProgress = findViewById(R.id.tvProgress);
        rvImagePreview = findViewById(R.id.rvImagePreview);
        
        // 设置RecyclerView
        rvImagePreview.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        imagePreviewAdapter = new ImagePreviewAdapter();
        rvImagePreview.setAdapter(imagePreviewAdapter);
        
        btnConvert.setEnabled(false);
    }

    private void setupListeners() {
        btnSelectImages.setOnClickListener(v -> selectImages());
        
        btnSelectAllHeic.setOnClickListener(v -> selectAllHeicImages());
        
        btnConvert.setOnClickListener(v -> {
            if (!selectedImages.isEmpty()) {
                convertImages();
            }
        });
    }
    
    private void selectAllHeicImages() {
        btnSelectAllHeic.setEnabled(false);
        btnSelectAllHeic.setText("扫描中...");
        
        executorService.execute(() -> {
            ArrayList<Uri> heicImages = new ArrayList<>();
            ContentResolver resolver = getContentResolver();
            Uri collection;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            } else {
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            }

            String[] projection = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.MIME_TYPE
            };
            String selection = MediaStore.Images.Media.MIME_TYPE + " IN (?, ?)";
            String[] selectionArgs = new String[]{"image/heic", "image/heif"};
            String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " DESC";

            try (Cursor cursor = resolver.query(collection, projection, selection, selectionArgs, sortOrder)) {
                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        Uri contentUri = ContentUris.withAppendedId(collection, id);
                        heicImages.add(contentUri);
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "扫描失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    btnSelectAllHeic.setEnabled(true);
                    btnSelectAllHeic.setText("一键选择全部");
                });
                return;
            }
            
            runOnUiThread(() -> {
                selectedImages.clear();
                selectedImages.addAll(heicImages);
                updateImageSelection();
                btnSelectAllHeic.setEnabled(true);
                btnSelectAllHeic.setText("一键选择全部");
                Toast.makeText(this, "找到 " + heicImages.size() + " 张HEIC图片", Toast.LENGTH_SHORT).show();
            });
        });
    }
    
    private void updateImageSelection() {
        btnConvert.setEnabled(!selectedImages.isEmpty());
        tvImageCount.setText("已选择: " + selectedImages.size() + " 张图片");
        imagePreviewAdapter.setImages(selectedImages);
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
        
        btnConvert.setEnabled(false);
        btnSelectImages.setEnabled(false);
        btnSelectAllHeic.setEnabled(false);
        int totalImages = selectedImages.size();
        successCount = 0;
        int[] convertedCount = {0};
        
        tvStatus.setText(""); // 清空状态
        tvProgress.setText("转换进度: 0/" + totalImages);

        for (Uri imageUri : selectedImages) {
            executorService.execute(() -> {
                String fileName = "converted_" + System.currentTimeMillis() + "." + format;
                Uri finalUri = null;
                
                try {
                    OutputStream fos;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        values.put(MediaStore.Images.Media.RELATIVE_PATH, 
                                Environment.DIRECTORY_PICTURES + "/HeicConverter");
                        
                        finalUri = getContentResolver().insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        
                        if (finalUri == null) {
                            throw new IOException("Failed to create new MediaStore entry.");
                        }
                        fos = getContentResolver().openOutputStream(finalUri);
                    } else {
                        File outputDir = new File(Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES), "HeicConverter");
                        if (!outputDir.exists()) {
                            outputDir.mkdirs();
                        }
                        File outputFile = new File(outputDir, fileName);
                        finalUri = Uri.fromFile(outputFile);
                        fos = new FileOutputStream(outputFile);
                    }

                    try (OutputStream f = fos) {
                        Glide.with(this)
                                .asBitmap()
                                .load(imageUri)
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .skipMemoryCache(true)
                                .transform(new Rotate(0)) // 保持原始方向
                                .submit()
                                .get()
                                .compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, f);
                    }

                    // 复制EXIF信息
                    copyExifData(imageUri, finalUri);

                    runOnUiThread(() -> {
                        convertedCount[0]++;
                        successCount++;
                        tvStatus.append("\n转换成功: " + fileName);
                        tvProgress.setText(String.format("转换进度: %d/%d (成功: %d)", 
                                convertedCount[0], totalImages, successCount));
                        
                        if (convertedCount[0] >= totalImages) {
                            btnConvert.setEnabled(true);
                            btnSelectImages.setEnabled(true);
                            btnSelectAllHeic.setEnabled(true);
                            Toast.makeText(MainActivity.this, 
                                    String.format("转换完成，共%d张，成功%d张", totalImages, successCount), 
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    if (finalUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        getContentResolver().delete(finalUri, null, null);
                    }
                    runOnUiThread(() -> {
                        convertedCount[0]++;
                        tvStatus.append("\n转换失败: " + e.getMessage());
                        tvProgress.setText(String.format("转换进度: %d/%d (成功: %d)", 
                                convertedCount[0], totalImages, successCount));
                        
                        if (convertedCount[0] >= totalImages) {
                            btnConvert.setEnabled(true);
                            btnSelectImages.setEnabled(true);
                            btnSelectAllHeic.setEnabled(true);
                        }
                    });
                }
            });
        }
    }

    private void copyExifData(Uri sourceUri, Uri destUri) {
        if (destUri == null) return;
        try {
            ExifInterface sourceExif = new ExifInterface(getContentResolver().openInputStream(sourceUri));
            ExifInterface destExif;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
                destUri.getScheme() != null && destUri.getScheme().equals("content")) {
                destExif = new ExifInterface(getContentResolver().openFileDescriptor(destUri, "rw").getFileDescriptor());
            } else {
                destExif = new ExifInterface(destUri.getPath());
            }

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