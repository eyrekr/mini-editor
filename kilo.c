#include <ctype.h>
#include <stdio.h>
#include <termios.h>
#include <unistd.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <string.h>
#include <time.h>

#define CONTROL(key) ((key) & 0x1f)
#define SHIFT(key) ((key) & 0x40)
#define ESC "\x1b"

/** UTIL FUNCTIONS ***********************************************************/

void die(const char *s) {
    write(STDOUT_FILENO, "\x1b[2J\x1b[H", 7);
    perror(s);
    exit(1);
}

int min(int a, int b) {
    return a < b ? a : b;
}

int max(int a, int b) {
    return a > b ? a : b;
}

/** BACK BUFFER **************************************************************/

struct BackBuffer {
    char *data;
    int length;
    int capacity;
} backBuffer;

void backBufferDestroy() {
    backBuffer.length = 0;
    backBuffer.capacity = 0;
    free(backBuffer.data);
    backBuffer.data = NULL;
}

void backBufferInit(int capacity) {
    backBuffer.length = 0;
    backBuffer.capacity = capacity;
    backBuffer.data = malloc(capacity * sizeof(char));
    atexit(backBufferDestroy);
}

void backBufferClear() {
    memset(backBuffer.data, '\0', backBuffer.length);
    backBuffer.length = 0;
}

void backBufferAppend(const char *data, int length) {
    if (backBuffer.length + length >= backBuffer.capacity) {
        die("back buffer capacity exceeded");
    }
    memcpy(&(backBuffer.data[backBuffer.length]), data, length);
    backBuffer.length += length;
}

void backBufferRender() {
    write(STDOUT_FILENO, backBuffer.data, backBuffer.length);
}

/** TERMINAL *****************************************************************/

struct termios originalTerminalMode;

void terminalReset() {
    tcsetattr(STDIN_FILENO, TCSAFLUSH, &originalTerminalMode);
}

void terminalRawMode() {
    tcgetattr(STDIN_FILENO, &originalTerminalMode);
    atexit(terminalReset);

    struct termios mode = originalTerminalMode;
    mode.c_iflag &= ~(BRKINT |  ICRNL | INPCK | ISTRIP | IXON);
    mode.c_oflag &= ~(OPOST);
    mode.c_cflag |= (CS8);
    mode.c_lflag &= ~(ECHO | ICANON | IEXTEN | ISIG);
    mode.c_cc[VMIN] = 0; // minimum number of bytes of input needed before read() can return
    mode.c_cc[VTIME] = 1; // maximum amount of time to wait before read() returns; it is in tenths of a second = 100ms
    tcsetattr(STDIN_FILENO, TCSAFLUSH, &mode);
}

void terminalCursorHome() {
    write(STDOUT_FILENO, ESC "[H", 3);
}

void terminalCursorOut() {
    write(STDOUT_FILENO, ESC "[999C\x1b[999B", 12);
}

void terminalReadCursorPosition() {
    write(STDOUT_FILENO, ESC "[6n", 4);
}

void terminalCursorHide() {
    write(STDOUT_FILENO, ESC "[?25l", 6);
}

void terminalCursorShow() {
    write(STDOUT_FILENO, ESC "[?25h", 6);
}

void terminalSetCursorPosition(int x, int y) {
    char buffer[32];
    snprintf(buffer, sizeof(buffer), ESC "[%d;%dH", y + 1, x + 1);
    write(STDOUT_FILENO, buffer, strlen(buffer));
}

void terminalClearScreen() {
    write(STDOUT_FILENO, ESC "[2J" ESC "[H", 7);
}

void terminalClearLine() {
    write(STDOUT_FILENO, ESC "[K", 3);
}

void terminalGetSize(int *rows, int *columns) {
    struct winsize size;
    if (ioctl(STDOUT_FILENO, TIOCGWINSZ, &size) >= 0 && size.ws_col > 0) {
        *rows = size.ws_row;
        *columns = size.ws_col;
    } else {
        terminalCursorOut();
        terminalReadCursorPosition();
        
        char buffer[32];
        unsigned int i = 0;
        while (i < sizeof(buffer) - 1) {
            if (read(STDIN_FILENO, &(buffer[i]), 1) != 1 || buffer[i] == 'R') {
                break;
            }
            i++;
        }
        buffer[i] = '\0';
        sscanf(buffer, ESC "[%d;%d", rows, columns);
    }
}

/** EDITOR *******************************************************************/

struct Line {
    char *chars;
    int length;
};

struct EditorState {
    int rows;
    int columns;
    int cx, cy; // cursor

    struct Line *lines;
    int lineCount;
    int lineOffset;

    char *filename;

    char *message;
    time_t messageTime;
} state;

void editorInit() {
    state.cx = 0;
    state.cy = 0;
    state.lines = malloc(sizeof(struct Line));
    state.lineCount = 0;
    state.lineOffset = 0;
    terminalGetSize(&state.rows, &state.columns);
    state.rows -= 1;
    state.filename = NULL;
    state.message = NULL;
    state.messageTime = 0;
}

void editorSetStatusMessage(char *message) {
    if (state.message != NULL) {
        free(state.message);
    }
    state.message = strdup(message);
    state.messageTime = time(NULL);
}

void editorOpenFile(char *filename) {
    if (state.filename) {
        free(state.filename);
    }
    state.filename = strdup(filename);

    FILE *file = fopen(filename, "r");
    if (!file) {
        die("failed to open file");
    }

    char *buffer = NULL;
    size_t capacity = 0;
    ssize_t length = 0;

    while ((length = getline(&buffer, &capacity, file)) != -1) {
        while (length > 0 && (buffer[length - 1] == '\n' || buffer[length - 1] == '\r')) {
            length--;
        }
        state.lines = realloc(state.lines, (1 + state.lineCount) * sizeof(struct Line));
        struct Line *line = &state.lines[state.lineCount];
        line->length = length;
        line->chars = malloc((length + 1) * sizeof(char));
        memcpy(line->chars, buffer, length);
        line->chars[length] = '\0';
        state.lineCount += 1;
    }

    fclose(file);
    if (buffer) {
        free(buffer);
    }
}

void editorDrawLines() {
    for (int y = 0; y < state.rows; y++) {
        int lineNumber = state.lineOffset + y;
        backBufferAppend(ESC "[K", 3);
        if (lineNumber < state.lineCount) {
            struct Line *line = &state.lines[lineNumber];
            backBufferAppend(line->chars, min(line->length, state.columns - 1));
        } else {
            backBufferAppend("~", 1);
        }
        backBufferAppend("\r\n", 2);
    }

    backBufferAppend(ESC "[7m", 4);
    char status[state.columns];
    int length = 0;
    if (state.message == NULL) {
        length = snprintf(status, sizeof(status), "%.20s - %d lines    line: %d  column: %d", 
            state.filename ? state.filename : "[no file]", 
            state.lineCount,
            state.lineOffset + state.cy,
            state.cx);
        length = min(length, state.columns);
        backBufferAppend(status, length);
    } else {
        length = min(strlen(state.message), state.columns);
        backBufferAppend(state.message, length);
        if (time(NULL) - state.messageTime > 5) {
            //free(state.message);
            state.message = NULL;
            state.messageTime = 0;
        }
    }
    while (length < state.columns) {
        backBufferAppend(" ", 1);
        length++;
    }
    backBufferAppend(ESC "[m", 3);
}

/** INPUT HANDLER ************************************************************/

enum Key {
    ESCAPE = 0x1b,
    ARROW_LEFT = 0x400,
    ARROW_RIGHT,
    ARROW_UP,
    ARROW_DOWN,
    PAGE_UP,
    PAGE_DOWN,
    HOME,
    END,
    DELETE
};

int readKey() {
    char c = 0;
    while (read(STDIN_FILENO, &c, 1) != 1);
    if (c == ESCAPE) {
        char sequence[3];
        for (int i = 0; i < 3 && read(STDIN_FILENO, &sequence[i], 1) == 1; i++);
        if (sequence[0] == '[') {
            switch (sequence[1]) {
            case 'A': return ARROW_UP;
            case 'B': return ARROW_DOWN;
            case 'C': return ARROW_RIGHT;
            case 'D': return ARROW_LEFT;
            case 'F': return END;
            case 'H': return HOME;
            
            case '1': return HOME;
            case '3': return DELETE;
            case '4': return END;
            case '5': return PAGE_UP;
            case '6': return PAGE_DOWN;
            case '7': return HOME;
            case '8': return END;
            }
        } else if (sequence[0] == 'O') {
            switch (sequence[1]) {
            case 'F': return END;
            case 'H': return HOME;
            }
        }
        return ESCAPE;
    }
    return c;
}

void handleKeyPress() {
    int c = readKey();
    
    switch (c) {
    case ESCAPE:
    case CONTROL('q'):
        terminalClearScreen();
        exit(0);
        break;
    case ARROW_LEFT:
        state.cx = max(0, state.cx - 1);
        break;
    case ARROW_RIGHT:
        state.cx = min(state.columns - 1, state.cx + 1);
        break;
    case ARROW_UP:
        state.cy = max(0, state.cy - 1);
        if (state.cy == 0) {
            state.lineOffset = max(state.lineOffset - 1, 0);
        }
        break;
    case ARROW_DOWN:
        state.cy = min(state.rows - 1, state.cy + 1);
        if (state.cy == state.rows - 1) {
            state.lineOffset = min(state.lineOffset + 1, state.lineCount);
        }
        break;
    case PAGE_UP:
        //state.cy = max(0, state.cy - state.rows);
        state.lineOffset = max(state.lineOffset - state.rows, 0);
        break;
    case PAGE_DOWN:
        //state.cy = max(0, state.cy + state.rows);
        state.lineOffset = min(state.lineOffset + state.rows, state.lineCount);
        break;
    case HOME:
        state.cx = 0;
        break;
    case END:
        state.cx = state.columns - 1;
        break;
    }
}

/** MAIN ENTRY POINT *********************************************************/

int main(int argc, char *argv[]) {
    terminalRawMode();
    editorInit();
    if (argc > 1) {
        editorOpenFile(argv[1]);
    }
    backBufferInit(state.columns * state.rows * 8);
    terminalClearScreen();

    editorSetStatusMessage("HELP: press CTRL+Q to quit");

    while (1) {
        terminalCursorHide();
        terminalCursorHome();
        backBufferClear();
        editorDrawLines();
        backBufferRender();
        terminalSetCursorPosition(state.cx, state.cy);
        terminalCursorShow();

        handleKeyPress();
    }
    return 0;
}
