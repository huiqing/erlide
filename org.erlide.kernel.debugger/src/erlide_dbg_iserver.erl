%% ``The contents of this file are subject to the Erlang Public License,
%% Version 1.1, (the "License"); you may not use this file except in
%% compliance with the License. You should have received a copy of the
%% Erlang Public License along with this software. If not, it can be
%% retrieved via the world wide web at http://www.erlang.org/.
%% 
%% Software distributed under the License is distributed on an "AS IS"
%% basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
%% the License for the specific language governing rights and limitations
%% under the License.
%% 
%% The Initial Developer of the Original Code is Ericsson Utvecklings AB.
%% Portions created by Ericsson are Copyright 1999, Ericsson Utvecklings
%% AB. All Rights Reserved.''
%% 
%%     $Id$
%%
-module(erlide_dbg_iserver).
-behaviour(gen_server).

%% External exports
-export([start/0, stop/0, find/0,
	 call/1, call/2, cast/1, cast/2, safe_call/1, safe_cast/1]).

%% gen_server callbacks
-export([init/1, handle_call/3, handle_cast/2, handle_info/2,
	 terminate/2, code_change/3]).

-record(proc,  {pid,           % pid() Debugged process
		meta,          % pid() Meta process
		attpid,        % pid() | undefined  Attached process
		status,        % running | exit | idle | waiting
		info     = {}, % {} | term()
		exit_info= {}, % {} | {{Mod,Line}, Bs, Stack}
		function       % {Mod,Func,Args} Initial function call
	       }).

-record(state, {db,            % ETS table
		procs    = [], % [#proc{}]
		breaks   = [], % [{{M,L},Options} Breakpoints
		auto,          % Auto attach settings
		stack,         % Stack trace settings
		subs     = []  % [pid()]   Subscribers (Debugger)
	       }).


%%====================================================================
%% External exports
%%====================================================================

start() ->
    gen_server:start({local, ?MODULE}, ?MODULE, [], []).

stop() ->
    gen_server:cast(?MODULE, stop).

find() ->
    global:whereis_name(?MODULE).

call(Request) ->
    gen_server:call(?MODULE, Request, infinity).

call(Int, Request) ->
    gen_server:call(Int, Request, infinity).

cast(Request) ->
    gen_server:cast(?MODULE, Request).

cast(Int, Request) ->
    gen_server:cast(Int, Request).

safe_call(Request) ->
    ensure_started(),
    call(Request).

safe_cast(Request) ->
    ensure_started(),
    cast(Request).

ensure_started() ->
    case whereis(?MODULE) of
	undefined -> start();
	_Pid -> ignore
    end.

%%--Module database---------------------------------------------------
%% This server creates an ETS table, where the following information
%% is saved:
%%
%% Key                        Value  
%% ---                        -----  
%% {Mod, refs}                [ModDb]
%% ModDb                      [pid()]
%%
%% In each ModDb, the following information is saved by erlide_dbg_iload:
%%
%% Key                        Value
%% ---                        -----
%% attributes                 Attr
%% exports                    Exp
%% defs                       []
%% mod_bin                    Binary
%% mod_file                   File
%% module                     Mod
%% {Mod,Name,Arity,Exported}  Cs
%% {'fun',Mod,Index,Uniq}     {Name,Arity,Cs}
%% Line                       {Pos,PosNL}


%%====================================================================
%% gen_server callbacks
%%====================================================================

init([]) ->
    process_flag(trap_exit, true),
    global:register_name(?MODULE, self()),
    Db = ets:new(?MODULE, [ordered_set, protected]),
    {ok, #state{db=Db, auto=false, stack=all}}.

%% Attaching to a process
handle_call({attached, AttPid, Pid}, _From, State) ->
    {true, Proc} = get_proc({pid, Pid}, State#state.procs),
    case Proc#proc.attpid of
	undefined ->
	    link(AttPid),
	    case Proc#proc.status of
		exit ->
		    Args = [self(),
			    AttPid,Pid,Proc#proc.info,
			    Proc#proc.exit_info],
		    Meta = spawn_link(erlide_dbg_ieval, exit_info, Args),
		    Proc2 = Proc#proc{meta=Meta, attpid=AttPid},
		    Procs = lists:keyreplace(Pid, #proc.pid,
					     State#state.procs, Proc2),
		    {reply, {ok,Meta}, State#state{procs=Procs}};
		_Status ->
		    Meta = Proc#proc.meta,
		    send(Meta, {attached, AttPid}),
		    Procs = lists:keyreplace(Pid, #proc.pid,
					     State#state.procs,
					     Proc#proc{attpid=AttPid}),
		    {reply, {ok, Meta}, State#state{procs=Procs}}
	    end;
	_AttPid -> % there is already an attached process
	    {reply, error, State}
    end;

%% Getting and setting options
handle_call(get_auto_attach, _From, State) ->
    {reply, State#state.auto, State};
handle_call(get_stack_trace, _From, State) ->
    {reply, State#state.stack, State};

%% Retrieving information
handle_call(snapshot, _From, State) ->
    Reply = lists:map(fun(Proc) ->
			      {Proc#proc.pid, Proc#proc.function,
			       Proc#proc.status, Proc#proc.info}
		      end,
		      State#state.procs),
    {reply, Reply, State};
handle_call(dump, _From, State) ->
    {reply, {record_info(fields, state), State}, State};
handle_call({get_meta, Pid}, _From, State) ->
    Reply = case get_proc({pid, Pid}, State#state.procs) of
		{true, Proc} ->
		    {ok, Proc#proc.meta};
		false ->
		    {error, not_interpreted}
	    end,
    {reply, Reply, State};

%% Breakpoint handling
handle_call({new_break, Point, Options}, _From, State) ->
    case lists:keysearch(Point, 1, State#state.breaks) of
	false ->
	    Break = {Point, Options},
	    send_all([subscriber, meta, attached],
		     {new_break, Break}, State),
	    Breaks = keyinsert(Break, 1, State#state.breaks),
	    {reply, ok, State#state{breaks=Breaks}};
	{value, _Break} ->
	    {reply, {error, break_exists}, State}
    end;
handle_call(all_breaks, _From, State) ->
    {reply, State#state.breaks, State};
handle_call({all_breaks, Mod}, _From, State) ->
    Reply = lists:filter(fun({{M,_L}, _Options}) ->
				 if M==Mod -> true; true -> false end
			 end,
			 State#state.breaks),
    {reply, Reply, State};

%% From Meta process
handle_call({new_process, Pid, Meta, Function}, _From, State) ->
    link(Meta),
    log({new_process, {pid, Pid}, {meta, Meta}, Function}),

    %% A new, debugged process has been started. Return its status,
    %% ie running (running as usual) or break (stop)
    %% The status depends on if the process is automatically attached to
    %% or not.
    Reply = case auto_attach(init, State#state.auto, Pid) of
		AttPid when is_pid(AttPid) -> break;
		ignore -> running
	    end,

    %% Do not add AttPid, it should call attached/2 when started instead
    Proc = #proc{pid=Pid, meta=Meta, status=running, function=Function},
    send_all(subscriber,
	     {new_process, {Pid,Function,running,{}}}, State),

    {reply, Reply, State#state{procs=State#state.procs++[Proc]}};

%% Code loading
handle_call({load, Mod, Src, Bin}, _From, State) ->

    %% Create an ETS table for storing information about the module
    Db = State#state.db,
    ModDb = ets:new(Mod, [ordered_set, public]),
    ModDbs = case ets:lookup(Db, {Mod, refs}) of
		 [] -> [];
		 [{{Mod, refs}, ModDbs1}] -> ModDbs1
	     end,
    ets:insert(Db, {{Mod, refs}, [ModDb|ModDbs]}),
    ets:insert(Db, {ModDb, []}),

    %% Load the code
    {ok, Mod} = erlide_dbg_iload:load_mod(Mod, Src, Bin, ModDb),

    %% Inform all subscribers and attached processes
    send_all([subscriber, attached], {interpret, Mod}, State),

    {reply, {module, Mod}, State};

%% Module database
handle_call({get_module_db, Mod, Pid}, _From, State) ->
    Db = State#state.db,
    Reply = case ets:lookup(Db, {Mod, refs}) of
		[] -> not_found;
		[{{Mod, refs}, [ModDb|_ModDbs]}] ->
		    [{ModDb, Pids}] = ets:lookup(Db, ModDb),
		    ets:insert(Db, {ModDb, [Pid|Pids]}),
		    ModDb
	    end,
    {reply, Reply, State};
handle_call({lookup, Mod, Key}, _From, State) ->
    Db = State#state.db,
    Reply = case ets:lookup(Db, {Mod, refs}) of
		[] -> not_found;
		[{{Mod, refs}, [ModDb|_ModDbs]}] ->
		    case ets:lookup(ModDb, Key) of
			[] -> not_found;
			[{Key, Value}] -> {ok, Value}
		    end
	    end,
    {reply, Reply, State};
handle_call({functions, Mod}, _From, State) ->
    Db = State#state.db,
    Reply = case ets:lookup(Db, {Mod, refs}) of
		[] -> [];
		[{{Mod, refs}, [ModDb|_ModDbs]}] ->
		    Pattern = {{Mod,'$1','$2','_'}, '_'},
		    ets:match(ModDb, Pattern)
	    end,
    {reply, Reply, State};
handle_call({contents, Mod, Pid}, _From, State) ->
    Db = State#state.db,
    [{{Mod, refs}, ModDbs}] = ets:lookup(Db, {Mod, refs}),
    ModDb = if
		Pid==any -> hd(ModDbs);
		true ->
		    lists:foldl(fun(T, not_found) ->
					[{T, Pids}] = ets:lookup(Db, T),
					case lists:member(Pid, Pids) of
					    true -> T;
					    false -> not_found
					end;
				   (_T, T) -> T
				end,
				not_found,
				ModDbs)
	    end,
    [{mod_bin, Bin}] = ets:lookup(ModDb, mod_bin),
    {reply, {ok, Bin}, State};
handle_call({is_interpreted, Mod, Name, Arity}, _From, State) ->
    Db = State#state.db,
    Reply = case ets:lookup(Db, {Mod, refs}) of
		[] -> false;
		[{{Mod, refs}, [ModDb|_ModDbs]}] ->
		    Pattern = {{Mod,Name,Arity,'_'}, '_'},
		    case ets:match_object(ModDb, Pattern) of
			[{_Key, Clauses}] -> {true, Clauses};
			[] -> false
		    end
	    end,
    {reply, Reply, State};
handle_call(all_interpreted, _From, State) ->
    Db = State#state.db,
    Mods = ets:select(Db, [{{{'$1',refs},'_'},[],['$1']}]),
    {reply, Mods, State};
handle_call({file, Mod}, From, State) ->
    {reply, Res, _} = handle_call({lookup, Mod, mod_file}, From, State),
    Reply = case Res of
		{ok, File} -> File;
		not_found -> {error, not_loaded}
	    end,
    {reply, Reply, State}.


handle_cast(stop, State) ->
    {stop, shutdown, State};
handle_cast({subscribe, Sub}, State) ->
    {noreply, State#state{subs=[Sub|State#state.subs]}};

%% Attaching to a process
handle_cast({attach, Pid, {Mod, Func, Args}}, State) ->
    %% Simply spawn process, which should call erlide_int:attached(Pid)
    spawn(Mod, Func, [Pid | Args]),
    {noreply, State};

%% Getting and setting options
handle_cast({set_auto_attach, false}, State) ->
    send_all(subscriber, {auto_attach, false}, State),
    {noreply, State#state{auto=false}};
handle_cast({set_auto_attach, Flags, Function}, State) ->
    send_all(subscriber, {auto_attach, {Flags, Function}}, State),
    {noreply, State#state{auto={Flags, Function}}};
handle_cast({set_stack_trace, Flag}, State) ->
    send_all(subscriber, {stack_trace, Flag}, State),
    {noreply, State#state{stack=Flag}};

%% Retrieving information
handle_cast(clear, State) ->
    Procs = lists:filter(fun(#proc{status=Status}) ->
				 if Status==exit -> false; true -> true end
			 end,
			 State#state.procs),
    {noreply, State#state{procs=Procs}};

%% Breakpoint handling
handle_cast({delete_break, Point}, State) ->
    case lists:keysearch(Point, 1, State#state.breaks) of
	{value, _Break} ->
	    send_all([subscriber, meta, attached],
		     {delete_break, Point}, State),
	    Breaks = lists:keydelete(Point, 1, State#state.breaks),
	    {noreply, State#state{breaks=Breaks}};
	false ->
	    {noreply, State}
    end;
handle_cast({break_option, Point, Option, Value}, State) ->
    case lists:keysearch(Point, 1, State#state.breaks) of
	{value, {Point, Options}} ->
	    N = case Option of
		    status -> 1;
		    action -> 2;
		    condition -> 4
		end,
	    Options2 = list_setelement(N, Options, Value),
	    send_all([subscriber, meta, attached],
		     {break_options, {Point, Options2}}, State),
	    Breaks = lists:keyreplace(Point, 1, State#state.breaks,
				      {Point, Options2}),
	    {noreply, State#state{breaks=Breaks}};
	false ->
	    {noreply, State}
    end;
handle_cast(no_break, State) ->
    send_all([subscriber, meta, attached], no_break, State),
    {noreply, State#state{breaks=[]}};
handle_cast({no_break, Mod}, State) ->
    send_all([subscriber, meta, attached], {no_break, Mod}, State),
    Breaks = lists:filter(fun({{M, _L}, _O}) ->
				  if M==Mod -> false; true -> true end
			  end,
			  State#state.breaks),
    {noreply, State#state{breaks=Breaks}};

%% From Meta process
handle_cast({set_status, Meta, Status, Info}, State) ->
    {true, Proc} = get_proc({meta, Meta}, State#state.procs),
    send_all(subscriber, {new_status, Proc#proc.pid, Status, Info}, State),
    if
	Status==break ->
	    auto_attach(break, State#state.auto, Proc);
	true -> ignore
    end,
    Proc2 = Proc#proc{status=Status, info=Info},
    {noreply, State#state{procs=lists:keyreplace(Meta, #proc.meta,
						 State#state.procs, Proc2)}};
handle_cast({set_exit_info, Meta, ExitInfo}, State) ->
    {true, Proc} = get_proc({meta, Meta}, State#state.procs),
    Procs = lists:keyreplace(Meta, #proc.meta, State#state.procs,
			     Proc#proc{exit_info=ExitInfo}),
    {noreply,State#state{procs=Procs}};

%% Code loading
handle_cast({delete, Mod}, State) ->
    log({delete, Mod}),
    %% Remove the ETS table with information about the module
    Db = State#state.db,
    case ets:lookup(Db, {Mod, refs}) of
	[] -> % Mod is not interpreted
	    {noreply, State};
	[{{Mod, refs}, ModDbs}] ->
            log(deleting),
	    ets:delete(Db, {Mod, refs}),
	    AllPids = lists:foldl(
			fun(ModDb, PidsAcc) ->
				[{ModDb, Pids}] = ets:lookup(Db, ModDb),
				ets:delete(Db, ModDb),
				ets:delete(ModDb),
				PidsAcc++Pids
			end,
			[],
			ModDbs),
	    lists:foreach(fun(Pid) ->
				  case get_proc({pid, Pid},
						State#state.procs) of
				      {true, Proc} ->
					  send(Proc#proc.meta,
					       {old_code, Mod});
				      false -> ignore % pid may have exited
				  end
			  end,
			  AllPids),

	    send_all([subscriber,attached], {no_interpret, Mod}, State),

	    %% Remove all breakpoints for Mod
	    handle_cast({no_break, Mod}, State)
    end.

%% Process exits
handle_info({'EXIT',Who,Why}, State) ->
    case get_proc({meta, Who}, State#state.procs) of

	%% Exited process is a meta process for exit_info
	{true,#proc{status=exit}} ->
	    {noreply,State};
	    
	%% Exited process is a meta process
	{true,Proc} ->
	    Pid = Proc#proc.pid,
	    ExitInfo = Proc#proc.exit_info,
	    %% Check if someone is attached to the debugged process,
	    %% if so a new meta process should be started
	    Meta = case Proc#proc.attpid of
		       AttPid when is_pid(AttPid) ->
			   spawn_link(erlide_dbg_ieval, exit_info, 
				      [self(),AttPid,Pid,Why,ExitInfo]);
		       undefined ->
			   %% Otherwise, auto attach if necessary
			   auto_attach(exit, State#state.auto, Pid),
			   Who
		   end,
	    send_all(subscriber, {new_status,Pid,exit,Why}, State),
	    Procs = lists:keyreplace(Who, #proc.meta, State#state.procs,
				     Proc#proc{meta=Meta,
					       status=exit,
					       info=Why}),
	    {noreply,State#state{procs=Procs}};

	false ->
	    case get_proc({attpid, Who}, State#state.procs) of
		
		%% Exited process is an attached process
		{true, Proc} ->
		    %% If status==exit, then the meta process is a
		    %% simple meta for a terminated process and can be
		    %% terminated as well (it is only needed by
		    %% the attached process)
		    case Proc#proc.status of
			exit -> send(Proc#proc.meta, stop);
			_Status -> send(Proc#proc.meta, detached)
		    end,
		    Procs = lists:keyreplace(Proc#proc.pid, #proc.pid,
					     State#state.procs,
					     Proc#proc{attpid=undefined}),
		    {noreply, State#state{procs=Procs}};

		%% Otherwise exited process must be a subscriber
		false ->
		    Subs = lists:delete(Who, State#state.subs),
		    {noreply, State#state{subs=Subs}}
	    end
    end.

terminate(_Reason, _State) ->
    EbinDir = filename:join(code:lib_dir(debugger), "ebin"),
    code:unstick_dir(EbinDir),
    ok.

code_change(_OldVsn, State, _Extra) ->
    {ok, State}.


%%====================================================================
%% Internal functions
%%====================================================================

auto_attach(Why, Auto, Proc) when is_record(Proc, proc) ->
    case Proc#proc.attpid of
	AttPid when is_pid(AttPid) -> ignore;
	undefined ->
	    auto_attach(Why, Auto, Proc#proc.pid)
    end;
auto_attach(Why, Auto, Pid) when is_pid(Pid) ->
    case Auto of
	false -> ignore;
	{Flags, {Mod, Func, Args}} ->
	    case lists:member(Why, Flags) of
		true ->
		    spawn(Mod, Func, [Pid | Args]);
		false -> ignore
	    end
    end.

keyinsert(Tuple1, N, [Tuple2|Tuples]) ->
    if
	element(N, Tuple1)<element(N, Tuple2) ->
	    [Tuple1, Tuple2|Tuples];
	true ->
	    [Tuple2 | keyinsert(Tuple1, N, Tuples)]
    end;
keyinsert(Tuple, _N, []) ->
    [Tuple].

list_setelement(N, L, E) -> list_setelement(1, N, L, E).

list_setelement(I, I, [_|T], E) ->
    [E|T];
list_setelement(I, N, [H|T], E) ->
    [H|list_setelement(I+1, N, T, E)].

mapfilter(Fun, [H|T]) ->
    case Fun(H) of
	ignore -> mapfilter(Fun, T);
	H2 -> [H2|mapfilter(Fun, T)]
    end;
mapfilter(_Fun, []) ->
    [].

send_all([Type|Types], Msg, State) ->
    send_all(Type, Msg, State),
    send_all(Types, Msg, State);
send_all([], _Msg, _State) -> ok;

send_all(subscriber, Msg, State) ->
    send_all(State#state.subs, Msg);
send_all(meta, Msg, State) ->
    Metas = lists:map(fun(Proc) -> Proc#proc.meta end, State#state.procs),
    send_all(Metas, Msg);
send_all(attached, Msg, State) ->
    AttPids= mapfilter(fun(Proc) ->
			       case Proc#proc.attpid of
				   Pid when is_pid(Pid) -> Pid;
				   undefined -> ignore
			       end
		       end,
		       State#state.procs),
    send_all(AttPids, Msg).

send_all(Pids, Msg) ->
    lists:foreach(fun(Pid) -> send(Pid, Msg) end, Pids).

send(Pid, Msg) ->
    Pid ! {int, Msg}.

get_proc({Type, Pid}, Procs) ->
    Index = case Type of
		pid -> #proc.pid;
		meta -> #proc.meta;
		attpid -> #proc.attpid
	    end,
    case lists:keysearch(Pid, Index, Procs) of
	{value, Proc} -> {true, Proc};
	false -> false
    end.

log(_) ->
    ok.
%% log(E) ->
%%     erlide_debug:log(E).
