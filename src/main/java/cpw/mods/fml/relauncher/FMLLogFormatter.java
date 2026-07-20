/*
 * Forge Mod Loader
 * Copyright (c) 2012-2013 cpw.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     cpw - implementation
 */

package cpw.mods.fml.relauncher;

/**
 * Copied from ConsoleLogFormatter for shared use on the client
 *
 */
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

final class FMLLogFormatter extends Formatter
{
    static final String LINE_SEPARATOR = System.getProperty("line.separator");
    private SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");

    public String format(LogRecord record)
    {
        StringBuilder msg = new StringBuilder();
        msg.append("[").append(this.dateFormat.format(Long.valueOf(record.getMillis()))).append("]");
        msg.append(" [").append(Thread.currentThread().getName()).append("/");
        Level lvl = record.getLevel();
        // Use English level name (INFO, WARN, ERROR…) to match Log4j style
        msg.append(lvl.getName()).append("]");
        msg.append(": ").append(formatMessage(record));
        msg.append(LINE_SEPARATOR);
        Throwable thr = record.getThrown();
        if (thr != null)
        {
            StringWriter thrDump = new StringWriter();
            thr.printStackTrace(new PrintWriter(thrDump));
            msg.append(thrDump.toString());
        }
        return msg.toString();
    }
}
