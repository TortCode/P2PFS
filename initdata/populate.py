import sys
from pathlib import Path

def main():
    assert len(sys.argv) > 2
    dirname = Path(sys.argv[1])
    filename = sys.argv[2]
    keyword = sys.argv[3]

    with (dirname / filename).open('w') as f:
        f.write(keyword+'\n')
        f.write(f"text for {filename}:{keyword}\n")

if __name__ == "__main__":
    main()