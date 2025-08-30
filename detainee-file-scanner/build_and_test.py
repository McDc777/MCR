# NO EXTERNAL NETWORK CALLS OR TELEMETRY

import os
import zipfile
import processor
import io

def create_zip():
    """Packages the application into detainee-file-scanner.zip."""

    # Ensure we are in the script's directory
    os.chdir(os.path.dirname(os.path.abspath(__file__)))

    files_to_zip = [
        'app.py',
        'processor.py',
        'requirements.txt',
        'README.md',
        'LICENSE',
        'build_and_test.py',
    ]
    # The zip file should be created in the root, sibling to the detainee-file-scanner dir
    with zipfile.ZipFile('detainee-file-scanner.zip', 'w') as zf:
        for f in files_to_zip:
            zf.write(f)

        example_dir = 'example_inputs'
        for root, _, files in os.walk(example_dir):
            for file in files:
                zf.write(os.path.join(root, file))

def run_smoke_test():
    """Runs a smoke test using the example files."""
    print("Running smoke test...")

    # Ensure we are in the script's directory
    os.chdir(os.path.dirname(os.path.abspath(__file__)))

    example_files = [os.path.join('example_inputs', f) for f in os.listdir('example_inputs')]

    all_text = ""
    for file_path in example_files:
        with open(file_path, 'rb') as f:
            # Create a mock Streamlit UploadedFile
            class MockUploadedFile(io.BytesIO):
                def __init__(self, initial_bytes, name):
                    super().__init__(initial_bytes)
                    self.name = name

            file_content = f.read()
            mock_file = MockUploadedFile(file_content, os.path.basename(file_path))
            all_text += processor.extract_text(mock_file) + "\n"

    if not all_text.strip():
        print("Smoke test FAILED: No text extracted.")
        return

    answers = processor.find_answers(all_text)
    if len(answers) != len(processor.QUESTIONS):
        print(f"Smoke test FAILED: Expected {len(processor.QUESTIONS)} answers, but got {len(answers)}.")
        return

    print("Smoke test PASSED.")
    print("Sample answers:")
    for i, answer in enumerate(answers):
        print(f"{i+1}. {processor.QUESTIONS[i]['question']}: {answer}")


if __name__ == "__main__":
    create_zip()
    run_smoke_test()
    print("\nBuild complete. detainee-file-scanner.zip created.")
