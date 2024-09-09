import sys
from pathlib import Path

def main():
    assert len(sys.argv) > 2
    dirname = Path(sys.argv[1])
    filename = sys.argv[2]

    with (dirname / filename).open('w') as f:
        print('Enter keyword:')
        line = input('> ')
        f.write(line+'\n')
        print()
        print('Enter text:')
        try:
            line = input('> ')
            while True:
                f.write(line+'\n')
                line = input('> ')
        except EOFError:
            pass

if __name__ == "__main__":
    main()