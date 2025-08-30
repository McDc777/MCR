# Detainee File Scanner

This tool is a local-only application for scanning detainee files to answer a specific set of questions. It is designed to be run on your local machine and does not send any data over the network.

## Security Warning

**The app reads sensitive information. Use responsibly and run locally.**

This tool is designed to work completely offline. It does not make any external network calls, and no data is sent to any external services. All file processing happens on your local machine.

## Installation and Usage

To run the application, you need Python 3.9 or later.

1.  **Install dependencies:**

    ```bash
    python -m pip install -r requirements.txt
    ```

2.  **Run the application:**

    ```bash
    streamlit run app.py
    ```

This will open the application in your web browser.

## Supported File Types

The tool supports the following file types:
*   PDF (`.pdf`)
*   Word (`.docx`)
*   Excel (`.xlsx`, `.xls`)
*   CSV ('.csv')
*   Plain Text (`.txt`)

**Note:** Older `.doc` files are not supported. Please convert them to `.docx` before processing.

## No API Keys Required

This tool does not require any API keys to operate.
