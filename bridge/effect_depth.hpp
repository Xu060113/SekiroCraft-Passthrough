#pragma once
#include <windows.h>
#include <GL/gl.h>
#include "MinHook.h"
#include <mutex>

namespace bridge::effectDepth {
// Effekseer calls OpenGL from native code, bypassing Java RenderSystem. Scope
// overrides to this rendering thread; ordinary MC draws keep their own states.
inline thread_local bool capturing=false;
using Mask=void (APIENTRY*)(GLboolean);
using ColorMask=void (APIENTRY*)(GLboolean,GLboolean,GLboolean,GLboolean);
using Toggle=void (APIENTRY*)(GLenum);
inline Mask originalDepthMask{};
inline ColorMask originalColorMask{};
inline Toggle originalDisable{},originalDepthFunc{};
inline void APIENTRY depthMask(GLboolean value){originalDepthMask(capturing?GL_TRUE:value);}
inline void APIENTRY colorMask(GLboolean r,GLboolean g,GLboolean b,GLboolean a){
    originalColorMask(capturing?GL_TRUE:r,capturing?GL_TRUE:g,capturing?GL_TRUE:b,capturing?GL_TRUE:a);
}
inline void APIENTRY disable(GLenum value){if(!capturing || value!=GL_DEPTH_TEST)originalDisable(value);}
inline void APIENTRY depthFunc(GLenum value){originalDepthFunc(capturing?GL_LEQUAL:value);}
inline bool install(){
    static std::once_flag once;static bool ready=false;
    std::call_once(once,[]{
        auto library=GetModuleHandleW(L"opengl32.dll");if(!library)return;
        auto status=MH_Initialize();if(status!=MH_OK && status!=MH_ERROR_ALREADY_INITIALIZED)return;
        void *targets[4]{},*detours[4]{reinterpret_cast<void*>(depthMask),reinterpret_cast<void*>(colorMask),
            reinterpret_cast<void*>(disable),reinterpret_cast<void*>(depthFunc)};
        void **originals[4]{reinterpret_cast<void**>(&originalDepthMask),reinterpret_cast<void**>(&originalColorMask),
            reinterpret_cast<void**>(&originalDisable),reinterpret_cast<void**>(&originalDepthFunc)};
        const char *names[4]{"glDepthMask","glColorMask","glDisable","glDepthFunc"};
        int created=0,enabled=0;
        for(;created<4;++created){targets[created]=reinterpret_cast<void*>(GetProcAddress(library,names[created]));
            if(!targets[created] || MH_CreateHook(targets[created],detours[created],originals[created])!=MH_OK)break;}
        if(created==4)for(;enabled<4;++enabled)if(MH_EnableHook(targets[enabled])!=MH_OK)break;
        ready=created==4 && enabled==4;
        if(!ready){for(int i=0;i<enabled;++i)MH_DisableHook(targets[i]);for(int i=0;i<created;++i)MH_RemoveHook(targets[i]);}
    });return ready;
}
inline bool begin(){
    if(capturing || !wglGetCurrentContext() || !install())return false;
    capturing=true;glEnable(GL_DEPTH_TEST);originalDepthFunc(GL_LEQUAL);
    originalDepthMask(GL_TRUE);originalColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);return true;
}
inline void end(){capturing=false;}
}
