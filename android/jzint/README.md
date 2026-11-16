# jzint条码生产库

jzint条码生成库是在C语言的zint库基础上，加入了java调用接口。Java接口中的所有属性和C库中属性一致。

使用方式

1. 在项目根build.gradle下配置仓库地址
```groovy
buildscript {
    allprojects {
        repositories {
            //佳博SDK仓库
            maven {
                url "http://118.31.6.84:8081/repository/maven-public/"
                allowInsecureProtocol true
            }
        }
    }
}
```
2. 在app的build.gradle添加jzint的包依赖
```groovy
dependencies {
    implementation 'com.gainscha:jzint:0.4.1'
}
```
3. 生成条码图片
```java
private Bitmap createQrcode(String content) {
    JzintSymbol symbol=new JzintSymbol();
    symbol.symbology=JzintSymbol.BARCODE_QRCODE;
    symbol.input_mode=JzintSymbol.DATA_MODE;
    symbol.width=500;
    symbol.height=500;
    symbol.scale=5;
    return JzInt.create(symbol,content,null,false,false,0,0);
}
```
参数含义
```java
  /**
     * 创建条码，非标准宽高
     *
     * @param symbol        条码参数
     * @param content       条码内容
     * @param typeface      文本字体
     * @param removeBlank   是否清除边界空白
     * @param antiAlias     是否抗锯齿
     * @param textLocation  文本位置
     * @param textAlignment 文本对齐方式
     */
    public static Bitmap create(JzintSymbol symbol, String content,
                                Typeface typeface, boolean removeBlank,
                                boolean antiAlias, int textLocation, int textAlignment) {
        
    }
```

JzintSymbol的具体参数和数值范围请参考zint官网文档

[zint官网](https://www.zint.org.uk/)