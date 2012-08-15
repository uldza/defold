#include "script.h"

#include "script_private.h"

#include <stdint.h>
#include <string.h>

#include <dlib/dstrings.h>
#include <dlib/math.h>
#include <dlib/message.h>
#include <dlib/log.h>

#include <ddf/ddf.h>

extern "C"
{
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

namespace dmScript
{
    struct LuaLoadData
    {
        const char* m_Buffer;
        uint32_t m_Size;
    };

    static const char* ReadScript(lua_State *L, void *data, size_t *size)
    {
        LuaLoadData* lua_load_data = (LuaLoadData*)data;
        if (lua_load_data->m_Size == 0)
        {
            return 0x0;
        }
        else
        {
            *size = lua_load_data->m_Size;
            lua_load_data->m_Size = 0;
            return lua_load_data->m_Buffer;
        }
    }

    static bool LoadScript(lua_State* L, const void* buffer, uint32_t buffer_size, const char* filename)
    {
        int top = lua_gettop(L);
        (void) top;

        LuaLoadData data;
        data.m_Buffer = (const char*)buffer;
        data.m_Size = buffer_size;
        int ret = lua_load(L, &ReadScript, &data, filename);
        if (ret == 0)
        {
            assert(top + 1 == lua_gettop(L));
            return true;
        }
        else
        {
            dmLogError("Error running script: %s", lua_tostring(L,-1));
            lua_pop(L, 1);
            assert(top == lua_gettop(L));
            return false;
        }
    }

    static int LoadModule (lua_State *L) {
        int top = lua_gettop(L);
        (void) top;

        lua_getglobal(L, SCRIPT_CONTEXT);
        HContext context = (HContext)lua_touserdata(L, -1);
        lua_pop(L, 1);

        const char *name = luaL_checkstring(L, 1);
        dmhash_t name_hash = dmHashString64(name);
        Module* module = context->m_Modules.Get(name_hash);

        if (module == NULL)
        {
            lua_pushfstring(L, "\n\tno file '%s'", name);
            assert(top + 1  == lua_gettop(L));
            return 1;
        }
        if (!LoadScript(L, module->m_Script, module->m_ScriptSize, name))
        {
            luaL_error(L, "error loading module '%s'from file '%s':\n\t%s",
                          lua_tostring(L, 1), name, lua_tostring(L, -1));
        }
        assert(top + 1 == lua_gettop(L));
        return 1;
    }

    Result AddModule(HContext context, const char* script, uint32_t script_size, const char* script_name, void* user_data)
    {
        dmhash_t module_hash = dmHashString64(script_name);

        Module module;
        module.m_Name = strdup(script_name);
        module.m_Script = strdup(script);
        module.m_ScriptSize = script_size;
        module.m_UserData = user_data;

        if (context->m_Modules.Full())
        {
            context->m_Modules.SetCapacity(127, context->m_Modules.Capacity() + 128);
        }

        context->m_Modules.Put(module_hash, module);

        return RESULT_OK;
    }

    void* GetModuleUserData(HContext context, dmhash_t module_hash)
    {
        return context->m_Modules.Get(module_hash)->m_UserData;
    }

    Result ReloadModule(HContext context, lua_State* L, const char* script, uint32_t script_size, dmhash_t module_hash)
    {
        int top = lua_gettop(L);
        (void) top;

        Module* module = context->m_Modules.Get(module_hash);
        if (module == 0)
        {
            return RESULT_MODULE_NOT_LOADED;
        }

        free(module->m_Script);

        module->m_Script = strdup(script);
        module->m_ScriptSize = script_size;

        if (LoadScript(L, script, script_size, module->m_Name))
        {
            lua_pushstring(L, module->m_Name);
            int ret = lua_pcall(L, 1, LUA_MULTRET, 0);
            if (ret != 0)
            {
                dmLogError("Error running script: %s", lua_tostring(L,-1));
                lua_pop(L, 1);
                assert(top == lua_gettop(L));
                return RESULT_LUA_ERROR;
            }
        }
        else
        {
            assert(top == lua_gettop(L));
            return RESULT_LUA_ERROR;
        }
        assert(top == lua_gettop(L));
        return RESULT_OK;
    }

    bool ModuleLoaded(HContext context, const char* script_name)
    {
        dmhash_t module_hash = dmHashString64(script_name);
        return context->m_Modules.Get(module_hash) != 0;
    }

    bool ModuleLoaded(HContext context, dmhash_t module_hash)
    {
        return context->m_Modules.Get(module_hash) != 0;
    }

    void InitializeModule(lua_State* L)
    {
        int top = lua_gettop(L);
        (void) top;
        lua_getfield(L, LUA_GLOBALSINDEX, "package");
        if (lua_istable(L, -1))
        {
            assert(lua_istable(L, -1));
            lua_getfield(L, -1, "loaders");
            assert(lua_istable(L, -1));

            int max_key = -1;
            lua_pushnil(L);
            while (lua_next(L, -2) != 0)
            {
                max_key = dmMath::Max(max_key, (int) luaL_checknumber(L, -2));
                lua_pop(L, 1);
            }

            lua_pushcfunction(L, LoadModule);
            lua_rawseti(L, -2, max_key + 1);

            lua_pop(L, 2);
        }
        else
        {
            // Bare-bone lua, no libs opened. We don't created the package table.
            lua_pop(L, 1);
        }
        assert(top == lua_gettop(L));
    }

}
