package com.gainscha.jzint;

/**
 * @author mxlei
 * @date 2020/12/19
 */
public class JzintSymbol {

    public static final int ZINT_VERSION_MAJOR = 2;
    public static final int ZINT_VERSION_MINOR = 8;
    public static final int ZINT_VERSION_RELEASE = 0;

    /* Tbarcode 7 codes */
    public static final int BARCODE_CODE11 = 1;
    public static final int BARCODE_C25MATRIX = 2;
    public static final int BARCODE_C25INTER = 3;
    public static final int BARCODE_C25IATA = 4;
    public static final int BARCODE_C25LOGIC = 6;
    public static final int BARCODE_C25IND = 7;
    public static final int BARCODE_CODE39 = 8;
    public static final int BARCODE_EXCODE39 = 9;
    public static final int BARCODE_EANX = 13;
    public static final int BARCODE_EANX_CHK = 14;
    public static final int BARCODE_EAN128 = 16;
    public static final int BARCODE_CODABAR = 18;
    public static final int BARCODE_CODE128 = 20;
    public static final int BARCODE_DPLEIT = 21;
    public static final int BARCODE_DPIDENT = 22;
    public static final int BARCODE_CODE16K = 23;
    public static final int BARCODE_CODE49 = 24;
    public static final int BARCODE_CODE93 = 25;
    public static final int BARCODE_FLAT = 28;
    public static final int BARCODE_RSS14 = 29;
    public static final int BARCODE_RSS_LTD = 30;
    public static final int BARCODE_RSS_EXP = 31;
    public static final int BARCODE_TELEPEN = 32;
    public static final int BARCODE_UPCA = 34;
    public static final int BARCODE_UPCA_CHK = 35;
    public static final int BARCODE_UPCE = 37;
    public static final int BARCODE_UPCE_CHK = 38;
    public static final int BARCODE_POSTNET = 40;
    public static final int BARCODE_MSI_PLESSEY = 47;
    public static final int BARCODE_FIM = 49;
    public static final int BARCODE_LOGMARS = 50;
    public static final int BARCODE_PHARMA = 51;
    public static final int BARCODE_PZN = 52;
    public static final int BARCODE_PHARMA_TWO = 53;
    public static final int BARCODE_PDF417 = 55;
    public static final int BARCODE_PDF417TRUNC = 56;
    public static final int BARCODE_MAXICODE = 57;
    public static final int BARCODE_QRCODE = 58;
    public static final int BARCODE_CODE128B = 60;
    public static final int BARCODE_AUSPOST = 63;
    public static final int BARCODE_AUSREPLY = 66;
    public static final int BARCODE_AUSROUTE = 67;
    public static final int BARCODE_AUSREDIRECT = 68;
    public static final int BARCODE_ISBNX = 69;
    public static final int BARCODE_RM4SCC = 70;
    public static final int BARCODE_DATAMATRIX = 71;
    public static final int BARCODE_EAN14 = 72;
    public static final int BARCODE_VIN = 73;
    public static final int BARCODE_CODABLOCKF = 74;
    public static final int BARCODE_NVE18 = 75;
    public static final int BARCODE_JAPANPOST = 76;
    public static final int BARCODE_KOREAPOST = 77;
    public static final int BARCODE_RSS14STACK = 79;
    public static final int BARCODE_RSS14STACK_OMNI = 80;
    public static final int BARCODE_RSS_EXPSTACK = 81;
    public static final int BARCODE_PLANET = 82;
    public static final int BARCODE_MICROPDF417 = 84;
    public static final int BARCODE_ONECODE = 85;
    public static final int BARCODE_PLESSEY = 86;

    /* Tbarcode 8 codes */
    public static final int BARCODE_TELEPEN_NUM = 87;
    public static final int BARCODE_ITF14 = 89;
    public static final int BARCODE_KIX = 90;
    public static final int BARCODE_AZTEC = 92;
    public static final int BARCODE_DAFT = 93;
    public static final int BARCODE_MICROQR = 97;

    /* Tbarcode 9 codes */
    public static final int BARCODE_HIBC_128 = 98;
    public static final int BARCODE_HIBC_39 = 99;
    public static final int BARCODE_HIBC_DM = 102;
    public static final int BARCODE_HIBC_QR = 104;
    public static final int BARCODE_HIBC_PDF = 106;
    public static final int BARCODE_HIBC_MICPDF = 108;
    public static final int BARCODE_HIBC_BLOCKF = 110;
    public static final int BARCODE_HIBC_AZTEC = 112;

    /* Tbarcode 10 codes */
    public static final int BARCODE_DOTCODE = 115;
    public static final int BARCODE_HANXIN = 116;

    /*Tbarcode 11 codes*/
    public static final int BARCODE_MAILMARK = 121;

    /* Zint specific */
    public static final int BARCODE_AZRUNE = 128;
    public static final int BARCODE_CODE32 = 129;
    public static final int BARCODE_EANX_CC = 130;
    public static final int BARCODE_EAN128_CC = 131;
    public static final int BARCODE_RSS14_CC = 132;
    public static final int BARCODE_RSS_LTD_CC = 133;
    public static final int BARCODE_RSS_EXP_CC = 134;
    public static final int BARCODE_UPCA_CC = 135;
    public static final int BARCODE_UPCE_CC = 136;
    public static final int BARCODE_RSS14STACK_CC = 137;
    public static final int BARCODE_RSS14_OMNI_CC = 138;
    public static final int BARCODE_RSS_EXPSTACK_CC = 139;
    public static final int BARCODE_CHANNEL = 140;
    public static final int BARCODE_CODEONE = 141;
    public static final int BARCODE_GRIDMATRIX = 142;
    public static final int BARCODE_UPNQR = 143;
    public static final int BARCODE_ULTRA = 144;
    public static final int BARCODE_RMQR = 145;

    // Output options
    public static final int BARCODE_NO_ASCII = 1;
    public static final int BARCODE_BIND = 2;
    public static final int BARCODE_BOX = 4;
    public static final int BARCODE_STDOUT = 8;
    public static final int READER_INIT = 16;
    public static final int SMALL_TEXT = 32;
    public static final int BOLD_TEXT = 64;
    public static final int CMYK_COLOUR = 128;
    public static final int BARCODE_DOTTY_MODE = 256;
    public static final int GS1_GS_SEPARATOR = 512;

    // Input data types
    public static final int DATA_MODE = 0;
    public static final int UNICODE_MODE = 1;
    public static final int GS1_MODE = 2;
    public static final int ESCAPE_MODE = 8;

    // Data Matrix specific options (option_3)
    public static final int DM_SQUARE = 100;
    public static final int DM_DMRE = 101;

    // QR, Han Xin, Grid Matrix specific options (option_3)
    public static final int ZINT_FULL_MULTIBYTE = 200;

    // Ultracode specific option (option_3)
    public static final int ULTRA_COMPRESSION = 128;

    // Warning and error conditions
    public static final int ZINT_WARN_INVALID_OPTION = 2;
    public static final int ZINT_WARN_USES_ECI = 3;
    public static final int ZINT_ERROR_TOO_LONG = 5;
    public static final int ZINT_ERROR_INVALID_DATA = 6;
    public static final int ZINT_ERROR_INVALID_CHECK = 7;
    public static final int ZINT_ERROR_INVALID_OPTION = 8;
    public static final int ZINT_ERROR_ENCODING_PROBLEM = 9;
    public static final int ZINT_ERROR_FILE_ACCESS = 10;
    public static final int ZINT_ERROR_MEMORY = 11;

    // Raster file types
    public static final int OUT_BUFFER = 0;
    public static final int OUT_SVG_FILE = 10;
    public static final int OUT_EPS_FILE = 20;
    public static final int OUT_EMF_FILE = 30;
    public static final int OUT_PNG_FILE = 100;
    public static final int OUT_BMP_FILE = 120;
    public static final int OUT_GIF_FILE = 140;
    public static final int OUT_PCX_FILE = 160;
    public static final int OUT_JPG_FILE = 180;
    public static final int OUT_TIF_FILE = 200;

    // Debug flags
    public static final int ZINT_DEBUG_PRINT = 1;
    public static final int ZINT_DEBUG_TEST = 2;


    public int symbology = BARCODE_CODE128;
    public int height;
    public int whitespace_width;
    public int border_width;
    public int output_options;
    public byte[] fgcolour = "000000FF".getBytes();
    public byte[] bgcolour = "FFFFFFFF".getBytes();
    public byte[] outfile = "out.png".getBytes();
    public float scale = 1f;
    public int option_1 = -1;
    public int option_2;
    public int option_3;
    public int show_hrt = 1;
    public int fontsize = 8;
    public int input_mode = DATA_MODE;
    public int eci;
    public byte[] text = new byte[128];
    public int rows;
    public int width;
    public byte[] primary = new byte[128];
    public byte[][] encoded_data = new byte[200][143];
    /* Largest symbol is 189 x 189 Han Xin */
    public int[] row_height = new int[200];
    public byte[] errtxt = new byte[100];
    public byte[] bitmap;
    public int bitmap_width;
    public int bitmap_height;
    public int bitmap_byte_length;
    public float dot_size = 4f / 5f;
    //struct zint_vector *vector;
    //struct zint_render *rendered;
    public int debug;
}
