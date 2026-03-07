package com.dearmoon.shield;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.dearmoon.shield.data.WhitelistManager;
import java.util.ArrayList;
import java.util.List;

public class WhitelistActivity extends AppCompatActivity {
    private WhitelistManager whitelistManager;
    private WhitelistAdapter adapter;
    private List<String> packageList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ── Edge-to-Edge Immersive Status Bar ───────────────────────────────
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        androidx.core.view.WindowInsetsControllerCompat insetsController =
                new androidx.core.view.WindowInsetsControllerCompat(
                        getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);
        // ───────────────────────────────────────────────────────────────────

        setContentView(R.layout.activity_whitelist);

        whitelistManager = new WhitelistManager(this);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbarWhitelist);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            toolbar.setNavigationOnClickListener(v -> onBackPressed());
        }
        // Push toolbar top padding to clear transparent status bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, insets.top, 0, 0);
            return windowInsets;
        });

        EditText etPackageName = findViewById(R.id.etPackageName);
        Button btnAdd = findViewById(R.id.btnAddWhitelist);
        RecyclerView rvWhitelist = findViewById(R.id.rvWhitelist);

        packageList = new ArrayList<>(whitelistManager.getWhitelist());
        adapter = new WhitelistAdapter(packageList);
        rvWhitelist.setLayoutManager(new LinearLayoutManager(this));
        rvWhitelist.setAdapter(adapter);

        btnAdd.setOnClickListener(v -> {
            String pkg = etPackageName.getText().toString().trim();
            if (!pkg.isEmpty()) {
                if (!packageList.contains(pkg)) {
                    whitelistManager.addPackage(pkg);
                    packageList.add(pkg);
                    adapter.notifyItemInserted(packageList.size() - 1);
                    etPackageName.setText("");
                } else {
                    Toast.makeText(this, "Already whitelisted", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private class WhitelistAdapter extends RecyclerView.Adapter<WhitelistAdapter.ViewHolder> {
        private final List<String> items;

        WhitelistAdapter(List<String> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_whitelist, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String pkg = items.get(position);
            holder.tvName.setText(pkg);
            holder.btnRemove.setOnClickListener(v -> {
                whitelistManager.removePackage(pkg);
                items.remove(position);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, items.size());
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName;
            ImageButton btnRemove;

            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvWhitelistedPackage);
                btnRemove = itemView.findViewById(R.id.btnRemoveWhitelist);
            }
        }
    }
}
