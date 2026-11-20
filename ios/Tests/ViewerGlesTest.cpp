// SPDX-License-Identifier: GPL-3.0-or-later
// Execute Android's production shader; compare every channel with the shared Metal mapping.
#include "../Viewer/Sampling.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <algorithm>
#include <cassert>
#include <cmath>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
GLuint shader(GLenum type,const std::string& text){
    GLuint s=glCreateShader(type);const char* p=text.c_str();glShaderSource(s,1,&p,nullptr);glCompileShader(s);
    GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);if(!ok){char log[4096];glGetShaderInfoLog(s,sizeof(log),nullptr,log);std::cerr<<log;assert(ok);}return s;
}
size_t run(const std::string& fragment,int width,int height,bool flip,float strength,bool corrected){
    const auto getDisplay=reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));assert(getDisplay);
    EGLDisplay display=getDisplay(EGL_PLATFORM_SURFACELESS_MESA,EGL_DEFAULT_DISPLAY,nullptr);assert(display!=EGL_NO_DISPLAY);
    assert(eglInitialize(display,nullptr,nullptr));assert(eglBindAPI(EGL_OPENGL_ES_API));
    EGLint attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig config;EGLint count;assert(eglChooseConfig(display,attributes,&config,1,&count)&&count==1);
    EGLint size[]={EGL_WIDTH,width,EGL_HEIGHT,height,EGL_NONE};auto surface=eglCreatePbufferSurface(display,config,size);assert(surface!=EGL_NO_SURFACE);
    EGLint version[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};auto context=eglCreateContext(display,config,EGL_NO_CONTEXT,version);assert(context!=EGL_NO_CONTEXT);
    assert(eglMakeCurrent(display,surface,surface,context));
    GLuint vs=shader(GL_VERTEX_SHADER,"attribute vec2 pos; varying vec2 coord; void main(){gl_Position=vec4(pos,0.,1.);coord=(pos+1.)*.5;}");
    GLuint fs=shader(GL_FRAGMENT_SHADER,fragment),program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);GLint ok;glGetProgramiv(program,GL_LINK_STATUS,&ok);assert(ok);glUseProgram(program);
    std::vector<unsigned char> input(width*height*4),output(input.size());
    for(int y=0;y<height;++y)for(int x=0;x<width;++x){const size_t i=(y*width+x)*4;input[i]=(x%(width/2))%251;input[i+1]=y%251;input[i+2]=x<width/2?20:210;input[i+3]=255;}
    GLuint texture;glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,input.data());
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,strength==0?GL_NEAREST:GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,strength==0?GL_NEAREST:GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    glUniform1i(glGetUniformLocation(program,"frame"),0);glUniform2f(glGetUniformLocation(program,"panelSize"),width,height);glUniform1f(glGetUniformLocation(program,"fittedStrength"),strength);glUniform1f(glGetUniformLocation(program,"flipY"),flip?1:0);
    const float vertices[]={-1,-1,1,-1,-1,1,1,1};GLuint buffer;glGenBuffers(1,&buffer);glBindBuffer(GL_ARRAY_BUFFER,buffer);glBufferData(GL_ARRAY_BUFFER,sizeof(vertices),vertices,GL_STATIC_DRAW);
    GLint attr=glGetAttribLocation(program,"pos");glEnableVertexAttribArray(attr);glVertexAttribPointer(attr,2,GL_FLOAT,GL_FALSE,0,nullptr);glViewport(0,0,width,height);glDisable(GL_DITHER);glDrawArrays(GL_TRIANGLE_STRIP,0,4);glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,output.data());assert(glGetError()==GL_NO_ERROR);
    size_t errors=0;int maximum=0;
    for(int y=0;y<height;++y)for(int x=0;x<width;++x){const int sy=flip?height-y-1:y;auto s=corrected?fittedSample(x+0.5f,sy+0.5f,width,height):FHSample{float(x),float(sy),unsigned(x/(width/2)),true};
        for(int c=0;c<3;++c){float expected=0;if(s.visible){int ax=std::floor(s.x),ay=std::floor(s.y),bx=std::min(ax+1,int((s.eye+1)*(width/2)-1)),by=std::min(ay+1,height-1);float fx=s.x-ax,fy=s.y-ay;
            auto value=[&](int xx,int yy){return input[(yy*width+xx)*4+c];};
            expected=(value(ax,ay)*(1-fx)+value(bx,ay)*fx)*(1-fy)+(value(ax,by)*(1-fx)+value(bx,by)*fx)*fy;
        }
        const int error=std::abs(int(std::lround(expected))-int(output[(y*width+x)*4+c]));maximum=std::max(maximum,error);if(error>(corrected?1:0))++errors;}
    }
    std::cout<<width<<'x'<<height<<" flip="<<flip<<" strength="<<strength<<" max="<<maximum<<" bad_channels="<<errors<<'\n';
    glDeleteBuffers(1,&buffer);glDeleteTextures(1,&texture);glDeleteProgram(program);glDeleteShader(vs);glDeleteShader(fs);
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(display,context);eglDestroySurface(display,surface);eglTerminate(display);
    return errors;
}
int main(int argc,char** argv){
    assert(argc==2);std::ifstream f(argv[1]);assert(f);std::string fragment{std::istreambuf_iterator<char>(f),{}};
    for(auto size:{std::pair{1920,1080},std::pair{2340,1080},std::pair{1280,720}})for(bool flip:{false,true}){
        assert(run(fragment,size.first,size.second,flip,0,false)==0);
        assert(run(fragment,size.first,size.second,flip,0.13f,true)==0);
    }
    assert(run(fragment,1920,1080,false,-0.13f,true)>1000);
    std::cout<<"PASS: every RGB channel, original Android shader, both orientations; bypass exact, fitted <=1; wrong coefficient fails\n";
}
