/*
 * This file is part of GriefPrevention, licensed under the MIT License (MIT).
 *
 * Copyright (c) bloodmc
 * Copyright (c) contributors
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
package me.ryanhamshire.griefprevention.configuration.category;

import com.google.common.collect.Maps;
import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;
import org.spongepowered.api.text.Text;

import java.util.Map;

@ConfigSerializable
public class BanCategory extends ConfigCategory {

    @Setting
    private Map<String, Text> banReasons = Maps.newHashMap();

    public void addBan(String permission, Text reason) {
        permission = permission.replace("griefprevention.flag.", "").toLowerCase();
        this.banReasons.put(permission, reason);
    }

    public void removeBan(String permission) {
        permission = permission.replace("griefprevention.flag.", "").toLowerCase();
        this.banReasons.remove(permission);
    }

    public Text getReason(String permission) {
        permission = permission.replace("griefprevention.flag.", "").toLowerCase();
        for (Map.Entry<String, Text> banEntry : this.banReasons.entrySet()) {
            if (permission.contains(banEntry.getKey())) {
                return banEntry.getValue();
            }
        }
        return null;
    }
}
