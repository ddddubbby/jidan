# (c) 2008 by BogDan Vatra < bogdan@licentia.eu >
# vim: set ts=4 sw=4 et :

project(zint)

set(CMAKE_INCLUDE_CURRENT_DIR ON)
#set(CMAKE_VERBOSE_MAKEFILE ON)

set(ZINT_VERSION_MAJOR 2)
set(ZINT_VERSION_MINOR 9)
set(ZINT_VERSION_RELEASE 1)
set(ZINT_VERSION "${ZINT_VERSION_MAJOR}.${ZINT_VERSION_MINOR}.${ZINT_VERSION_RELEASE}")

add_definitions(-DZINT_VERSION=\"${ZINT_VERSION}\" -Wall)


set(zint_COMMON_SRCS common.c library.c large.c reedsol.c gs1.c eci.c general_field.c sjis.c gb2312.c gb18030.c)
set(zint_ONEDIM_SRCS code.c code128.c 2of5.c upcean.c telepen.c medical.c plessey.c rss.c)
set(zint_POSTAL_SRCS postal.c auspost.c imail.c mailmark.c)
set(zint_TWODIM_SRCS code16k.c codablock.c dmatrix.c pdf417.c qr.c maxicode.c composite.c aztec.c code49.c code1.c gridmtx.c hanxin.c dotcode.c ultra.c)
set(zint_OUTPUT_SRCS vector.c ps.c svg.c emf.c bmp.c pcx.c gif.c png.c tif.c raster.c output.c)
set(zint_SRCS ${zint_OUTPUT_SRCS} ${zint_COMMON_SRCS} ${zint_ONEDIM_SRCS} ${zint_POSTAL_SRCS} ${zint_TWODIM_SRCS})

if (NOT PNG_FOUND)
    add_definitions(-DNO_PNG)
endif ()

add_library(zint-static STATIC ${zint_SRCS})

