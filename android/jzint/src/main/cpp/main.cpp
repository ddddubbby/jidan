#include <stdio.h>
#include <string.h>

#include "backend/zint.h"

class ZintCode {
protected:
    struct zint_symbol *symbol;

public:
    ZintCode() {
        symbol = ZBarcode_Create();
    }

    ~ZintCode() {
        if (symbol) {
            ZBarcode_Clear(symbol);
            ZBarcode_Delete(symbol);
            symbol = NULL;
        }
    }

    int pixel_is_set(const int x_coord, const int y_coord) {
        if (symbol->symbology == BARCODE_ULTRA) {
            return symbol->encoded_data[y_coord][x_coord];
        } else {
            return (symbol->encoded_data[y_coord][x_coord / 7] >> (x_coord % 7)) & 1;
        }
    }

    int gen_gs1_datamatrix(const char *input) {
        int r;

        symbol->symbology = BARCODE_DATAMATRIX;
        symbol->input_mode = GS1_MODE;
        // symbol->input_mode = UNICODE_MODE; // unicode : utf8
        symbol->output_options = 0;

        r = ZBarcode_Encode(symbol, (unsigned char *) input, strlen(input));
        if (r != 0) {
            fprintf(stderr, "Error: failed to encode : %s\n", symbol->errtxt);
            return r;
        }

        return r;
    }

    int print_datamatrix() {
        if (symbol->symbology != BARCODE_DATAMATRIX) {
            fprintf(stderr, "Error: invalid code type.\n");
            return -1;
        }

#define CONSOLE_USE_MONO_FONT 0

#if CONSOLE_USE_MONO_FONT
        static const char * white = "█";
        static const char * black = " ";
#else
        static const char *white = "██";
        static const char *black = "  ";
#endif
        printf("\n");
        for (int x = 0; x < symbol->width + 2; x++) {
            printf("%s", white);
        }
        printf("\n");

        // --------------------------------------------

        for (int x = 0; x < symbol->width; x++) {
            printf("%s", white);
            for (int y = 0; y < symbol->height; y++) {
                if (pixel_is_set(y, x)) {
                    printf("%s", black);
                } else {
                    printf("%s", white);
                }
            }
            printf("%s\n", white);
        }

        // --------------------------------------------

        for (int x = 0; x < symbol->width + 2; x++) {
            printf("%s", white);
        }
        printf("\n");
        printf("\n");

        return 0;
    }
};

// ./zint_gs1 "[01]12345678901231[30]19[21]123456789012"

int main(int argc, char **argv) {
    char *text = (char *) "[01]12345678901231[30]19[21]123456789012";
    if (argc > 1) {
        text = argv[1];
    }

    ZintCode zc;

    int r = zc.gen_gs1_datamatrix(text);
    if (r != 0) {
        return r;
    }

    return zc.print_datamatrix();
}
