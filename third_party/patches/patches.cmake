# Autark's changes to llama.cpp, kept as patches so that the submodule stays on upstream's tag:
# each patched file is written into the build tree and compiled in place of the original.
# Include this after llama.cpp's add_subdirectory(), then for each patch:
#
#   autark_patch_llama(${LLAMA_SRC} src/llama-model-loader.cpp llama-load-in-parallel.patch)
#
# llama-load-in-parallel.patch: weights that the CPU backend repacks as they load are set on
# every core instead of one, which was most of a phone's load time.

function(autark_patch_llama llama_src file patch)
    set(source ${llama_src}/${file})
    set(patch ${CMAKE_CURRENT_FUNCTION_LIST_DIR}/${patch})
    set(patched ${CMAKE_BINARY_DIR}/autark-patched/${file})
    get_filename_component(dir ${patched} DIRECTORY)
    file(MAKE_DIRECTORY ${dir})
    file(REMOVE ${patched})
    execute_process(
        COMMAND patch --silent --reject-file=- -o ${patched} ${source} ${patch}
        RESULT_VARIABLE failed)
    if(failed)
        message(FATAL_ERROR "${patch} doesn't apply to ${source}: update it for this llama.cpp")
    endif()
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${source} ${patch})
    set_source_files_properties(${source} TARGET_DIRECTORY llama PROPERTIES HEADER_FILE_ONLY ON)
    target_sources(llama PRIVATE ${patched})
endfunction()
