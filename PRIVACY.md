# Privacy integration

This SDK sends support content, customer identifiers and any customer profile
fields supplied by the host's token through the configured Daykeeper gateway.
The backend may retain those records; an in-memory-only Android client does not
make the overall support service storage-free. Complete the host app's Google
Play Data safety form, privacy policy and retention/erasure disclosure for its
actual backend configuration before shipping.

No SDK analytics, ad identifier, contact-list access, location permission, push
token collection, persistent transcript storage or background job is installed.
The library declares INTERNET permission. Its HTTP dependency uses AndroidX
Startup; review the merged host manifest and all transitive dependencies. The
host controls network security policy and OS backup settings.

The messenger disables view-state saving for content, platform autofill and
content capture where supported, and requests no personalized IME learning.
These flags do not make arbitrary keyboards, accessibility services, screen
recorders or host analytics safe. Exclude support content and token endpoints
from session replay/logging. Apply your app's screenshot/recents policy to the
host window; the offline example uses FLAG_SECURE. The library does not silently
change window-level screenshot policy for the rest of your app.

Backgrounding redacts messenger views and cancels current work, while preserving
drafts only in that session's memory. Process death loses them. Logout/customer
switch must call reset to clear drafts and drop the client/token provider.
Resetting the client does not delete server records. Use authorized server-side
erasure workflows for retention requests, never a customer management credential.
