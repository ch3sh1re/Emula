package dev.dunno.emula;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Map;

final class DiagnosticsEmail {
    static Intent draft(Context context, String diagnostics) {
        String address = context.getString(R.string.diagnostics_email_address);
        Intent email = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null))
                .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_email_subject,
                        Build.MANUFACTURER, Build.MODEL))
                .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.diagnostics_email_body, diagnostics));
        PackageManager packages = context.getPackageManager();
        ResolveInfo preferred = packages.resolveActivity(email, PackageManager.MATCH_DEFAULT_ONLY);
        Map<String, Intent> drafts = new LinkedHashMap<>();
        for (ResolveInfo handler : packages.queryIntentActivities(email, PackageManager.MATCH_DEFAULT_ONLY)) {
            String name = handler.activityInfo.packageName;
            // Some composers read EXTRA_TEXT only for ACTION_SEND.
            Intent share = new Intent(Intent.ACTION_SEND).setPackage(name).setType("text/plain")
                    .putExtras(email).putExtra(Intent.EXTRA_EMAIL, new String[]{address});
            Intent target = share.resolveActivity(packages) != null ? share : new Intent(email).setPackage(name);
            if (preferred != null && name.equals(preferred.activityInfo.packageName)) return target;
            drafts.put(name, target);
        }
        if (drafts.isEmpty()) return email;
        Intent[] choices = drafts.values().toArray(new Intent[0]);
        if (choices.length == 1) return choices[0];
        Intent chooser = Intent.createChooser(choices[0], context.getText(R.string.diagnostics_open_email));
        return chooser.putExtra(Intent.EXTRA_ALTERNATE_INTENTS,
                java.util.Arrays.copyOfRange(choices, 1, choices.length));
    }

    private DiagnosticsEmail() {}
}
