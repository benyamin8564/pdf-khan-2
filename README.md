# Smart PDF Price Finder AI

Android app for searching tool-price PDF catalogs in Persian/Arabic/English.

Features:
- Add multiple PDF files and keep them in an in-app file manager.
- Remove selected PDFs without deleting the original files.
- Extract text from normal PDFs with PDFBox.
- OCR fallback for scanned/image PDFs using Persian, Arabic and English Tesseract models.
- Smart local ranking: exact product code, name/description matching, Persian/Arabic normalization, digit normalization and fuzzy matching.
- Shows price, product text, source PDF and page number.
- GitHub Actions workflow builds a debug APK and uploads it as an artifact.

The search intelligence is local and does not require an API key. No cloud AI key is embedded in the APK.
