/*
 * Copyright (c) 2016 SUGRA-SYM LLC (Nathan Wiehoff, Geoffrey Hibbert)
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

/*
 * Input box for asking the player things.
 */
package gdi.component;

import gdi.Utility;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 *
 * @author nwiehoff
 */
public class AstralInput extends AstralLabel {

    private boolean canReturn = false;

    public AstralInput() {
        setFocusColor(Color.PINK);
        setBackColor(windowBlue);
    }

    @Override
    public void handleKeyReleasedEvent(String ke, boolean shiftDown) {
        switch (ke) {
            case "KEY_BACKSPACE":
                if (getText().length() > 0) {
                    setText(getText().substring(0, getText().length() - 1));
                }
                break;
            case "KEY_RETURN":
                //return
                setVisible(false);
                setCanReturn(true);
                break;
            case "KEY_SPACE":
                setText(getText() + " ");
                break;
            case "KEY_MINUS":
                setText(getText() + "-");
                break;
            case "KEY_LSHIFT":
                break;
            case "KEY_RSHIFT":
                break;
            default:
                String in = ke.split("_")[1];
                if (shiftDown) {
                    in = in.toUpperCase();
                } else {
                    in = in.toLowerCase();
                }
                setText(getText() + in);
                break;
        }
    }

    @Override
    public void setVisible(boolean visible) {
        this.visible = visible;
        canReturn = false;
    }

    public boolean canReturn() {
        return canReturn;
    }

    public void setCanReturn(boolean canReturn) {
        this.canReturn = canReturn;
    }

    @Override
    public void render(Graphics f) {
        if (visible) {
            if (buffer == null) {
                buffer = Utility.CreateCompatibleImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
            }
            Graphics2D s = (Graphics2D) buffer.getGraphics();
            //draw the background
            s.setColor(backColor);
            s.fillRect(0, 0, getWidth(), getHeight());
            //draw focus color
            if (focused) {
                s.setColor(focusColor);
                s.drawRect(0, 0, width - 1, height - 1);
            }
            //draw the text
            s.setFont(font);
            s.setColor(fontColor);
            s.drawString(text, 1, font.getSize());
            //push
            f.drawImage(buffer, x, y, null);
            //clear
            s.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
            s.fillRect(0, 0, getWidth(), getHeight());
        }
    }
}
