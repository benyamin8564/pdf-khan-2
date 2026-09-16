# Smart PDF Price Finder AI

Android app for searching tool-price PDF catalogs in Persian/Arabic/English.

## UI fixes
- Buttons use density-independent pixels (dp) instead of raw pixels, so they keep a usable size on different phone screen densities.
- Add PDF and file-management buttons are constrained to one line with readable text and safe padding.
- Search field and result cards also use dp-based spacing/heights.

## Features
- Add multiple PDF files and keep them in an in-app file manager.
- Remove selected PDFs without deleting the original files.
- Extract text from normal PDFs with PDFBox.
- OCR fallback for scanned/image PDFs using Persian, Arabic and English Tesseract models.
- Smart local ranking: exact product code, name/description matching, Persian/Arabic normalization, digit normalization and fuzzy matching.
- Shows price, product text, source PDF and page number.

The search intelligence is local and does not require an API key. No cloud AI key is embedded in the APK.
