import java.util.LinkedList;
import java.util.Arrays;
import java.util.Random;

public class Box {

    /**
     * Intended for probing values of keys; 
     * press a key and see how it is encoded.
     */
    static void inputProbe() throws Exception {
        while (true) {
            Ascii key = Console.readNonBlocking();
            if (key != Ascii.Nothing) {
                Console.clearScreen();

                for (byte value : Console.buffer) {
                    if (value > 0) {
                        System.out.printf("%x", value);
                    }
                }

                System.out.printf("\r\n%c   %s", key.character > 0 ? key.character : ' ', key);

                if (key == Ascii.EndOfText || key == Ascii.EndOfTransmission) {
                    System.exit(0);
                } 
            }
            Thread.sleep(1L);
        }
    }

    /**
     * MAIN ENTRY POINT
     */
    public static void main(String... args) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Console.cookedMode();
            Console.showCursor();
        }));
        Console.rawMode();
        Console.hideCursor();
        Console.clearScreen();

        //inputProbe();
        new TextEditor().start();
    }
}

class Layer {
    final int width;
    final int height;
    final char[] buffer;

    int column = 0;
    int row = 0;
    boolean dirty = false;

    Layer(int width, int height) {
        this.width = width;
        this.height = height;
        this.buffer = new char[width * height];
    }

    Layer erase() {
        this.column = 0;
        this.row = 0;
        this.dirty = false;
        Arrays.fill(this.buffer, '\0');
        return this;
    }

    Layer move(int columnDelta, int rowDelta) {
        return column(column + columnDelta).row(row + rowDelta);
    }

    Layer column(int column) {
        this.dirty = true;
        this.column = (column + width) % width;
        return this;
    }

    Layer row(int row) {
        this.dirty = true;
        this.row = (row + height) % height;
        return this;
    }

    Layer put(char character) {
        dirty = true;
        buffer[row * width + column] = character;
        return this;
    }

    Layer put(int column, int row, char character) {
        dirty = true;
        buffer[row * width + column] = character;
        return this;
    }

    char get() {
        return this.buffer[row * this.width + column];
    }
}

/** 
 * Intended for emulating a very simple editor.
 * Just to test how we can handle input.
 */
class TextEditor {
    Layer layer = new Layer(80, 25);
    Layer overlay = new Layer(80, 25); // for animation magic
    boolean quit = false;

    LinkedList<Effect> effects = new LinkedList<>();

    void handleKeyboardInput() {
        Ascii key = Console.readNonBlocking();
        if (key != Ascii.Nothing) {
            layer.dirty = true;
            switch (key) {
            case ArrowUp:
                layer.move(0, -1);
                break;
            case ArrowDown:
                layer.move(0, +1);
                break;
            case ArrowLeft:
                layer.move(-1, 0);
                break;
            case ArrowRight:
                layer.move(+1, 0);
                break;
            case Enter:
                layer.column(0).move(0, +1);
                break;
            case Backspace:
                layer.move(-1, 0).put('\0');
                break;
            case EndOfText:
            case EndOfTransmission:
                quit = true;
                break;
            default:
                if (key.character > 0) {
                    effects.add(new ChaoticCharacter(layer.column, layer.row, 3));
                    layer.put(key.character).move(+1, 0);
                } else {
                    layer.dirty = false;
                }
            }
        }
    }

    void applyEffects() {
        overlay.erase().column(layer.column).row(layer.row);
        for (var iterator = effects.iterator(); iterator.hasNext(); ) {
            Effect effect = iterator.next();
            if (!effect.apply(overlay)) {
                iterator.remove();
            }
        }
    }

    void start() throws Exception {
        effects.add(new BlinkingCursor());
        //effects.add(new FpsCounter());
        while (!quit) {
            handleKeyboardInput();
            applyEffects();
            if (layer.dirty || overlay.dirty) {
                Console.render(layer, overlay);
                layer.dirty = false;
                overlay.dirty = false;
            }
            Thread.sleep(10L);
        }
    }
}

@FunctionalInterface
interface Effect {
    /// Do something to layer.
    /// Return false if the effect is no longer applicable and shall be removed.
    boolean apply(Layer layer);
}

class BlinkingCursor implements Effect {
    @Override
    public boolean apply(Layer layer) {
        boolean appears = (System.currentTimeMillis() / 500) % 2 == 0;
        if (appears) {
            layer.put('_');
        }
        return true;
    }
}

class ChaoticCharacter implements Effect {
    static final String PALETTE = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!@#$%^&*_+";

    final int column;
    final int row;
    final int count;
    final Random random = new Random();
    int i = 1;

    ChaoticCharacter(int column, int row, int count) {
        this.column = column;
        this.row = row;
        this.count = count;
    }

    @Override
    public boolean apply(Layer layer) {
        if (i > count) {
            return false;
        }
        layer.put(column, row, PALETTE.charAt(random.nextInt(PALETTE.length())));
        i += 1;
        return true;
    }
}

class FpsCounter implements Effect {
    static final String DIGIT = "0123456789";

    long lastTick = System.nanoTime();

    @Override
    public boolean apply(Layer layer) {
        int ms = (int)((System.nanoTime() - lastTick) / 1_000_000L); // ms to render one frame
        int fps = ms > 0 ? 1_000 / ms : 0;
        lastTick = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            layer.put(79 - i, 24, DIGIT.charAt(fps % 10));
            fps /= 10;
        }
        return true;
    }
}

class Console {
    static final byte[] buffer = new byte[32];

    static void rawMode() {
        try {
            Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "stty raw -echo </dev/tty"});
        } catch(Exception e) {
        }
    }
    
    static void cookedMode() {
        try {
            Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "stty -raw echo </dev/tty"});
        } catch(Exception e) {
        }
    }

    // static Size size() {
    //     try {
    //         String signals = "\033[s\033[5000;5000H\033[6n\033[u";
    //         System.out.print(signals);
    //         byte[] bytes = new byte[32];
    //         int bytesRead = System.in.read(bytes);

    //         for (int i = 0; i < bytesRead; i++) {
    //             System.out.printf("%x", bytes[i]);
    //             // 1b 5b 34 31 3b 31 37 39 52
    //             // ^[41;179R 
    //         }
    //         System.out.print("\r\n");
    //     } catch(Exception e) {
    //     }
    //     return new Size(10,10);
    // }
    
    static void clearScreen() {
        System.out.print("\033[2J\033[H");
    }

    static void moveCursorTopLeft() {
        System.out.print("\033[0;0H");
    }

    static void hideCursor() {
        System.out.print("\033[?25l");
    }

    static void showCursor() {
        System.out.print("\033[?25h");
    }

    static Ascii readNonBlocking() {
        try {
            if (System.in.available() > 0) {
                Arrays.fill(buffer, (byte) 0);
                System.in.read(buffer); 
                return Ascii.from(buffer);
            }
            return Ascii.Nothing;
        } catch (Exception e) {
            return Ascii.Error;
        }
    }

    static void render(Layer layer, Layer overlay) {        
        moveCursorTopLeft();
        for (int row = 0; row < Math.min(25, layer.height); row++) {
            for (int column = 0; column < Math.min(80, layer.width); column++) {
                int i = layer.width * row + column;
                System.out.print(firstNotNull(overlay.buffer[i], layer.buffer[i], ' '));
            }
            System.out.print("\r\n");
        }
    }

    static char firstNotNull(char... characters) {
        for (char ch : characters) {
            if (ch != '\0') {
                return ch;
            }
        }
        return ' ';
    }
}

enum Ascii {
    Nothing(-2),
    EndOfText(encode("03")),
    EndOfTransmission(encode("04")),
    Tab(encode("09")),
    Enter(encode("0d")),
    FormFeed(encode("0c")),
    CarriageReturn(encode("0d")),
    Escape(encode("1b")),
    Space(encode("20"), ' '),
    ExclamationMark(encode("21"), '!'),
    DoubleQuotes(encode("22"), '"'),
    Hashtag(encode("23"), '#'),
    DollarSign(encode("24"), '$'),
    Percent(encode("25"), '%'),
    Ampersand(encode("26"), '&'),
    Tick(encode("27"),'\''),
    OpenParen(encode("28"), '('),
    CloseParen(encode("29"), ')'),
    Asterisk(encode("2a"), '*'),
    Plus(encode("2b"), '+'),
    Comma(encode("2c"), ','),
    Minus(encode("2d"), '-'),
    Dot(encode("2e"), '.'),
    Slash(encode("2f"), '/'),
    NumZero(encode("30"), '0'),
    NumOne(encode("31"), '1'),
    NumTwo(encode("32"), '2'),
    NumThree(encode("33"), '3'),
    NumFour(encode("34"), '4'),
    NumFive(encode("35"), '5'),
    NumSix(encode("36"), '6'),
    NumSeven(encode("37"), '7'),
    NumEight(encode("38"), '8'),
    NumNine(encode("39"), '9'),
    Colon(encode("3a"), ':'),
    Semicolon(encode("3b"), ';'),
    SmallerThan(encode("3c"), '<'),
    Equals(encode("3d"), '='),
    BiggerThan(encode("3e"), '>'),
    QuestionMark(encode("3f"), '?'),
    AtSign(encode("40"), '@'),
    CapitalA(encode("41"), 'A'),
    CapitalB(encode("42"), 'B'),
    CapitalC(encode("43"), 'C'),
    CapitalD(encode("44"), 'D'),
    CapitalE(encode("45"), 'E'),
    CapitalF(encode("46"), 'F'),
    CapitalG(encode("47"), 'G'),
    CapitalH(encode("48"), 'H'),
    CapitalI(encode("49"), 'I'),
    CapitalJ(encode("4a"), 'J'),
    CapitalK(encode("4b"), 'K'),
    CapitalL(encode("4c"), 'L'),
    CapitalM(encode("4d"), 'M'),
    CapitalN(encode("4e"), 'N'),
    CapitalO(encode("4f"), 'O'),
    CapitalP(encode("50"), 'P'),
    CapitalQ(encode("51"), 'Q'),
    CapitalR(encode("52"), 'R'),
    CapitalS(encode("53"), 'S'),
    CapitalT(encode("54"), 'T'),
    CapitalU(encode("55"), 'U'),
    CapitalV(encode("56"), 'V'),
    CapitalW(encode("57"), 'W'),
    CapitalX(encode("58"), 'X'),
    CapitalY(encode("59"), 'Y'),
    CapitalZ(encode("5a"), 'Z'),
    OpenBracket(encode("5b"), '['),
    Backslash(encode("5c"), '\\'),
    CloseBracket(encode("5d"), ']'),
    Roof(encode("5e"), '^'),
    Underscore(encode("5f"), '_'),
    Backtick(encode("60"), '`'),
    A(encode("61"), 'a'),
    B(encode("62"), 'b'),
    C(encode("63"), 'c'),
    D(encode("64"), 'd'),
    E(encode("65"), 'e'),
    F(encode("66"), 'f'),
    G(encode("67"), 'g'),
    H(encode("68"), 'h'),
    I(encode("69"), 'i'),
    J(encode("6a"), 'j'),
    K(encode("6b"), 'k'),
    L(encode("6c"), 'l'),
    M(encode("6d"), 'm'),
    N(encode("6e"), 'n'),
    O(encode("6f"), 'o'),
    P(encode("70"), 'p'),
    Q(encode("71"), 'q'),
    R(encode("72"), 'r'),
    S(encode("73"), 's'),
    T(encode("74"), 't'),
    U(encode("75"), 'u'),
    V(encode("76"), 'v'),
    W(encode("77"), 'w'),
    X(encode("78"), 'x'),
    Y(encode("79"), 'y'),
    Z(encode("7a"), 'z'),
    OpenCurly(encode("7b"), '{'),
    Pipe(encode("7c"), '|'),
    CloseCurly(encode("7d"), '}'),
    Tilde(encode("7e"), '~'),
    Backspace(encode("7f")),
    ArrowUp(encode("1b", "5b", "41")),
    ArrowDown(encode("1b", "5b", "42")),
    ArrowRight(encode("1b", "5b", "43")),
    ArrowLeft(encode("1b", "5b", "44")),
    F1(encode("1b", "4f", "50")),
    F2(encode("1b", "4f", "51")),
    F3(encode("1b", "4f", "52")),
    F4(encode("1b", "4f", "53")),
    F5(encode("1b", "5b", "31", "35", "7e")),
    F6(encode("1b", "5b", "31", "37", "7e")),
    F7(encode("1b", "5b", "31", "38", "7e")),
    F8(encode("1b", "5b", "31", "39", "7e")),
    F9(encode("1b", "5b", "32", "30", "7e")),
    F10(encode("1b", "5b", "32", "31", "7e")),
    ShiftTab(encode("1b", "5b", "5a")),
    Unsupported(-1),
    Error(-3);

    final long code;
    final char character;

    Ascii(long code, char character) {
        this.code = code;
        this.character = character;
    }

    Ascii(long code) {
        this(code, '\0');
    }

    static long encode(String... string) {
        long code = 0L;
        for (int i = 0; i < string.length; i++) {
            code |= Long.parseLong(string[i], 16) << (8*i);
        }
        return code;
    }

    static long encode(byte[] bytes) {
        long code = 0L;
        for (int i = 0; i < 8; i++) {
            code |= ((long) bytes[i]) << (8*i);
        }
        return code;
    }

    static Ascii from(byte[] bytes) {
        long code = encode(bytes);
        for (Ascii key : Ascii.values()) {
            if (key.code == code) {
                return key;
            }
        }
        return Ascii.Unsupported;
    }
}
