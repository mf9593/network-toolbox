package com.networktool.app.model;

/**
 * 主题颜色模型
 */
public class ThemeColor {
    private String name;
    private int color;
    private int colorDark;
    private boolean isSelected;
    private boolean isRandom;
    private int randomColor; // 保存生成的随机颜色

    public ThemeColor(String name, int color, int colorDark) {
        this.name = name;
        this.color = color;
        this.colorDark = colorDark;
        this.isSelected = false;
        this.isRandom = false;
        this.randomColor = 0;
    }

    public ThemeColor(String name, boolean isRandom) {
        this.name = name;
        this.isRandom = isRandom;
        this.color = 0;
        this.colorDark = 0;
        this.randomColor = 0;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public int getColorDark() {
        return colorDark;
    }

    public void setColorDark(int colorDark) {
        this.colorDark = colorDark;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }

    public boolean isRandom() {
        return isRandom;
    }

    public void setRandom(boolean random) {
        isRandom = random;
    }

    public int getRandomColor() {
        return randomColor;
    }

    public void setRandomColor(int randomColor) {
        this.randomColor = randomColor;
    }
}
