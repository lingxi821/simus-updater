/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2022 LSPosed Contributors
 */

//
// Created by Nullptr on 2022/5/11.
//

#pragma once

#include <map>
#include <string>

#include "core/config_bridge.h"

namespace lspd {

// LSPatch never obfuscates the framework. The JNI bridges fall back to their literal
// org/matrix/vector/nativebridge/ names when a key is absent (see jni_bridge.h), but
// resources_hook.cpp's GetXResourcesClassName() has no such fallback: it *requires* the
// "android.content.res.XRes" key and refuses to hook resources without it. So the map carries that
// one identity entry, which resolves back to android.content.res.XResources.
class ConfigImpl : public vector::native::ConfigBridge {
public:
    inline static void Init() { instance_ = std::make_unique<ConfigImpl>(); }

    std::map<std::string, std::string>& obfuscation_map() override { return obfuscation_map_; }

    void obfuscation_map(std::map<std::string, std::string> m) override {
        obfuscation_map_ = std::move(m);
    }

private:
    std::map<std::string, std::string> obfuscation_map_ = {
        {"android.content.res.XRes", "android.content.res.XRes"},
    };
};

}  // namespace lspd
