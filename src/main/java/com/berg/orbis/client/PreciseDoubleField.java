package com.berg.orbis.client;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.string.StringControllerElement;
import dev.isxander.yacl3.gui.controllers.string.number.DoubleFieldController;

import java.text.NumberFormat;

/**
 * A number field that keeps what is typed. YACL's own field shows its value with the system number format, which
 * keeps three decimals, and reads the shown text back when the field loses focus: 60.39299 became 60.393, about
 * 110 m off (50 blocks at 1:2). This one shows up to seven decimals (1 cm) in the same format it parses, so it works
 * with any system language, and applies each change at once, so pressing Done straight after typing keeps it.
 */
final class PreciseDoubleField extends DoubleFieldController {

    PreciseDoubleField(Option<Double> option, double min, double max) {
        super(option, min, max);
    }

    @Override
    public String getString() {
        NumberFormat format = (NumberFormat) NUMBER_FORMAT.clone();
        format.setMaximumFractionDigits(7);
        format.setGroupingUsed(false);
        return format.format(option().pendingValue());
    }

    @Override
    public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> widgetDimension) {
        return new StringControllerElement(this, screen, widgetDimension, true);
    }
}
